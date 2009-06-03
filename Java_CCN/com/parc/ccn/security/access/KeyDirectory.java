package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;

import javax.jcr.AccessDeniedException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.util.Arrays;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.sun.tools.javac.util.Pair;

/**
 * A key directory is a versioned key object which contains, under
 * the version, a set of key blocks wrapped under different target keys,
 * plus some potential supporting information pointing to previous
 * or subsequent versions of this key. This structure is used for
 * representing both node keys and group (private) keys. We
 * encapsulate functionality to walk such a directory and find our
 * target key here.
 * 
 * @author smetters
 *
 */
public class KeyDirectory extends EnumeratedNameList {
	
	AccessControlManager _manager; // to get at key cache
	HashMap<String, Timestamp> _principals = new HashMap<String, Timestamp>();
	ArrayList<byte []> _keyIDs = new ArrayList<byte []>();
	ArrayList<byte []> _otherNames = new ArrayList<byte []>();
	
	/**
	 * Directory name should be versioned, else we pull the latest version
	 * @param manager
	 * @param directoryName
	 * @param library
	 * @throws IOException
	 */
	public KeyDirectory(AccessControlManager manager, ContentName directoryName, CCNLibrary library) throws IOException {
		super(directoryName, library);
		if (null == manager) {
			stopEnumerating();
			throw new IllegalArgumentException("Manager cannot be null.");
		}	
		_manager = manager;
		initialize();
	}

	private void initialize() throws IOException {
		if (!VersioningProfile.isVersioned(_namePrefix)) {
			getNewData();
			ContentName latestVersionName = getLatestVersionChildName();
			if (null == latestVersionName) {
				Library.logger().info("Unexpected: can't get a latest version for key directory name : " + _namePrefix);
				getNewData();
				latestVersionName = getLatestVersionChildName();
				if (null == latestVersionName) {
					Library.logger().info("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
					throw new IOException("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
				}
			}
			synchronized (_childLock) {
				stopEnumerating();
				_children.clear();
				_newChildren = null;
				_namePrefix = latestVersionName;
				_enumerator.registerPrefix(_namePrefix);
			}
		}
	}
	
	/**
	 * Called each time new data comes in, gets to parse it and load processed
	 * arrays.
	 */
	protected void processNewChildren(ArrayList<ContentName> newChildren) {
		for (ContentName childName : newChildren) {
			// currently encapsulated in single-component ContentNames
			byte [] wkChildName = childName.lastComponent();
			if (AccessControlProfile.isWrappedKeyNameComponent(wkChildName)) {
				byte[] keyid;
				try {
					keyid = AccessControlProfile.getTargetKeyIDFromNameComponent(wkChildName);
					_keyIDs.add(keyid);
				} catch (IOException e) {
					Library.logger().info("Unexpected " + e.getClass().getName() + " parsing key id " + DataUtils.printHexBytes(wkChildName) + ": " + e.getMessage());
					// ignore and go on
				}
			} else if (AccessControlProfile.isPrincipalNameComponent(wkChildName)) {
				addPrincipal(wkChildName);
			} else {
				_otherNames.add(wkChildName);
			}
		}
	}
	
	public ArrayList<byte []> getWrappingKeyIDs() { return _keyIDs; }
	
	public HashMap<String, Timestamp> getPrincipals() { return _principals; }
	
	public ArrayList<byte []> otherNames() { return _otherNames; }
	
	protected void addPrincipal(byte [] wkChildName) {
		Pair<String, Timestamp> pair = AccessControlProfile.parsePrincipalInfoFromNameComponent(wkChildName);
		_principals.put(pair.fst, pair.snd);
	}
	
	public WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws XMLStreamException, IOException {
		if (!_keyIDs.contains(keyID)) {
			return null;
		}
		
		ContentName wrappedKeyName = new ContentName(_namePrefix, AccessControlProfile.targetKeyIDToNameComponent(keyID));
		return getWrappedKey(wrappedKeyName);
	}
	
	public WrappedKeyObject getWrappedKeyForPrincipal(String principalName) throws IOException, XMLStreamException {
		
		if (!_principals.containsKey(principalName)) {
			return null;
		}
		
		ContentName principalLinkName = new ContentName(_namePrefix, AccessControlProfile.principalInfoToNameComponent(principalName, _principals.get(principalName)));
		// This should be a link to the actual key block
		// TODO DKS replace link handling
		Link principalLink = _manager.library().getLink(principalLinkName, AccessControlManager.DEFAULT_TIMEOUT);
		Library.logger().info("Retrieving wrapped key for principal " + principalName + " at " + principalLink.getTargetName());
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}
	
	public boolean hasSupersededBlock() {
		return _otherNames.contains(AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	public ContentName getSupersededBlockName() {
		return ContentName.fromNative(_namePrefix, AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	/**
	 * Superseded block contains a wrapped key object, whose wrapping key
	 * name points to the key directory with the superseding key (either
	 * node key or wrapping private). That directory contains a PreviousKey
	 * block which contains a link to the previous key. This way lets you go
	 * forwards and backwards; if the wrapped block was under the previous
	 * key you could only go forwards in time.
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public WrappedKeyObject getSupersededWrappedKey() throws XMLStreamException, IOException {
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}
	
	WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws XMLStreamException, IOException {
		WrappedKeyObject wrappedKey = new WrappedKeyObject(wrappedKeyName, _manager.library());
		wrappedKey.update();
		return wrappedKey;		
	}
	
	public boolean hasPreviousKeyBlock() {
		return _otherNames.contains(AccessControlProfile.PREVIOUS_KEY_NAME);
	}

	public ContentName getPreviousKeyBlockName() {
		return getPreviousKeyBlockName(_namePrefix);
	}
	
	public static ContentName getPreviousKeyBlockName(ContentName keyDirectoryName) {
		return ContentName.fromNative(keyDirectoryName, AccessControlProfile.PREVIOUS_KEY_NAME);		
	}
	
	public LinkReference getPreviousKey() throws XMLStreamException, IOException {
		if (!hasPreviousKeyBlock())
			return null;
		Link previousKey = _manager.library().getLink(getPreviousKeyBlockName(), AccessControlManager.DEFAULT_TIMEOUT);
		if (null == previousKey) {
			Library.logger().info("Unexpected: no previous key link at " + getPreviousKeyBlockName());
			return null;
		}
		return previousKey.getReference();
	}

	/**
	 * We store a private key as a single block wrapped under a nonce key, which is
	 * then wrapped under the public keys of various principals. The WrappedKey structure
	 * would allow us to do this (wrap private in public) in a single object, with
	 * an inline nonce key, but this option is more efficient.
	 * @return
	 */
	public boolean hasPrivateKeyBlock() {
		return _otherNames.contains(AccessControlProfile.GROUP_PRIVATE_KEY_NAME);
	}

	public ContentName getPrivateKeyBlockName() {
		return ContentName.fromNative(_namePrefix, AccessControlProfile.GROUP_PRIVATE_KEY_NAME);
	}
	
	public WrappedKeyObject getPrivateKeyObject() throws IOException, XMLStreamException {
		if (!hasPrivateKeyBlock())
			return null;
		
		return new WrappedKey.WrappedKeyObject(getPrivateKeyBlockName(), _manager.library());
	}
	
	/**
	 * Find a copy of the key block in this directory that we can unwrap (either the private
	 * key wrapping key block or a wrapped raw symmetric key). Chase superseding keys if
	 * we have to. This mechanism should be generic, and should work for node keys
	 * as well as private key wrapping keys in directories following this structure.
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public Key getUnwrappedKey(byte [] expectedKeyID) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException {
		
		WrappedKeyObject wko = null;
		Key unwrappedKey = null;
		byte [] retrievedKeyID = null;
		// Do we have one of the wrapping keys already in our cache?
		// (This list will be empty only if this key is GONE. If it is, we'll move on
		// to a superseding key below if there is one.)
		// Do we have one of the wrapping keys in our cache?
		for (byte [] keyid : getWrappingKeyIDs()) {
			if (_manager.keyCache().containsKey(keyid)) {
				// We have it, pull the block, unwrap the node key.
				wko = getWrappedKeyForKeyID(keyid);
				if (null != wko.wrappedKey()) {
					unwrappedKey = wko.wrappedKey().unwrapKey(_manager.keyCache().getPrivateKey(keyid));
				}
			}
		}
		
		if (null == unwrappedKey) {
			// Not in cache. Is it superseded?
			if (hasSupersededBlock()) {
				// OK, is the superseding key just a newer version of this key? If it is, roll
				// forward to the latest version and work back from there.
				WrappedKeyObject supersededKeyBlock = getSupersededWrappedKey();
				if (null != supersededKeyBlock) {
					// We could just walk up superseding key hierarchy, and then walk back with
					// decrypted keys. Or we could attempt to jump all the way to the end and then
					// walk back. Not sure there is a significant win in doing the latter, so start
					// with the former... have to touch intervening versions in both cases.
					Library.logger().info("Attempting to retrieve key " + getName() + " by retrieving superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					
					Key unwrappedSupersedingKey = null;
					KeyDirectory supersedingKeyDirectory = null;
					try {
						supersedingKeyDirectory = new KeyDirectory(_manager, supersededKeyBlock.wrappedKey().wrappingKeyName(), _manager.library());
						// This wraps the key we actually want.
						unwrappedSupersedingKey = supersedingKeyDirectory.getUnwrappedKey(supersededKeyBlock.wrappedKey().wrappingKeyIdentifier());
					} finally {
						supersedingKeyDirectory.stopEnumerating();
					}
					if (null != unwrappedSupersedingKey) {
						_manager.keyCache().addKey(supersedingKeyDirectory.getName(), unwrappedSupersedingKey);
						unwrappedKey = supersededKeyBlock.wrappedKey().unwrapKey(unwrappedSupersedingKey);
					} else {
						Library.logger().info("Unable to retrieve superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					}
				}

			} else {
				// This is the current key. Enumerate principals and see if we can get a key to unwrap.
				Library.logger().info("At latest version of key " + getName() + ", attempting to unwrap.");
				// Assumption: if this key was encrypted directly for me, I would have had a cache
				// hit already. The assumption is that I pre-load my cache with my own private key(s).
				// So I don't care about principal entries if I get here, I only care about groups.
				// Groups may come in three types: ones I know I am a member of, but don't have this
				// particular key version for, ones I don't know anything about, and ones I believe
				// I'm not a member of but someone might have added me.
				if (_manager.haveKnownGroupMemberships()) {
					for (String principal : getPrincipals().keySet()) {
						if ((!_manager.isGroup(principal)) || (!_manager.amKnownGroupMember(principal))) {
							// On this pass, only do groups that I think I'm a member of. Do them
							// first as it is likely faster.
							continue;
						}
						// I know I am a member of this group, or at least I was last time I checked.
						// Attempt to get this version of the group private key as I don't have it in my cache.
						Key principalKey = _manager.getVersionedPrivateKeyForGroup(this, principal);
						unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
						if (null == unwrappedKey)
							break;
					}
				}
				if (null == unwrappedKey) {
					// OK, we don't have any groups we know we are a member of. Do the other ones.
					// Slower, as we crawl the groups tree.
					for (String principal : getPrincipals().keySet()) {
						if ((!_manager.isGroup(principal)) || (_manager.amKnownGroupMember(principal))) {
							// On this pass, only do groups that I don't think I'm a member of
							continue;
						}
						if (_manager.amCurrentGroupMember(principal)) {
							Key principalKey = _manager.getVersionedPrivateKeyForGroup(this, principal);
							unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
							if (null == unwrappedKey)
								break;
						}
					}
				}
			}
		}
		
		if (null != unwrappedKey) {
			_manager.keyCache().addKey(getName(), unwrappedKey);

			if (null != expectedKeyID) {
				retrievedKeyID = NodeKey.generateKeyID(unwrappedKey);
				if (!Arrays.areEqual(expectedKeyID, retrievedKeyID)) {
					Library.logger().warning("Retrieved and decrypted wrapped key, but it was the wrong key. We wanted " + 
							DataUtils.printBytes(expectedKeyID) + ", we got " + DataUtils.printBytes(retrievedKeyID));
				}
			}
		}
		return unwrappedKey;
	}
		
	protected Key unwrapKeyForPrincipal(String principal, Key unwrappingKey) throws IOException, XMLStreamException, InvalidKeyException, InvalidCipherTextException {
		
		Key unwrappedKey = null;
		if (null == unwrappingKey) {
			Library.logger().info("Null unwrapping key. Cannot unwrap.");
			return null;
		}
		WrappedKeyObject wko = getWrappedKeyForPrincipal(principal);
		if (null != wko.wrappedKey()) {
			unwrappedKey = wko.wrappedKey().unwrapKey(unwrappingKey);
		} else {
			Library.logger().info("Unexpected: retrieved version " + getPrincipals().get(principal) + " of " + principal + " group key, but cannot retrieve wrapped key object.");
		}
		return unwrappedKey;
	}
		
	/**
	 * Relies on caller, who presumably knows the public key, to add the result to the
	 * cache.
	 * @return
	 * @throws AccessDeniedException
	 * @throws IOException
	 * @throws XMLStreamException
	 * @throws InvalidKeyException
	 * @throws InvalidCipherTextException
	 */
	public PrivateKey getPrivateKey() throws IOException, XMLStreamException, InvalidKeyException, InvalidCipherTextException {
		if (!hasPrivateKeyBlock()) {
			Library.logger().info("No private key block exists with name " + getPrivateKeyBlockName());
			return null;
		}
		WrappedKeyObject wko = getPrivateKeyObject();
		if ((null == wko) || (null == wko.wrappedKey())) {
			Library.logger().info("Cannot retrieve wrapped private key for " + getPrivateKeyBlockName());
			return null;
		}
		// This should throw AccessDeniedException...
		Key wrappingKey = getUnwrappedKey(wko.wrappedKey().wrappingKeyIdentifier());
		if (null == wrappingKey) {
			Library.logger().info("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
			throw new AccessDeniedException("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
		}
		
		Key unwrappedPrivateKey = wko.wrappedKey().unwrapKey(wrappingKey);
		if (!(unwrappedPrivateKey instanceof PrivateKey)) {
			Library.logger().info("Unwrapped private key is not an instance of PrivateKey! Its an " + unwrappedPrivateKey.getClass().getName());
		} else {
			Library.logger().info("Unwrapped private key is a private key, in fact it's a " + unwrappedPrivateKey.getClass().getName());
		}
		return (PrivateKey)unwrappedPrivateKey;
	}

	public void addWrappedKeyBlock(Key privateKeyWrappingKey, ContentName name,
			PublicKey publicKey) {
		// TODO Auto-generated method stub
		
	}
}