/*
 * Copyright 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect.Backends.LoopbackBackend;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Backends.LanBackend.LanPairingHandler;
import org.kde.kdeconnect.Backends.PairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

public class LoopbackLink extends BaseLink {

    PrivateKey privateKey;
    PublicKey publicKey;

    public LoopbackLink(Context context,BaseLinkProvider linkProvider) {
        super(context,"loopback", linkProvider);
    }

    @Override
    public void initialiseLink() {
        // setting private key
        try {
            SharedPreferences globalSettings = PreferenceManager.getDefaultSharedPreferences(super.getContext());
            byte[] privateKeyBytes = Base64.decode(globalSettings.getString("privateKey", ""), 0);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/Device", "Exception reading our own private key"); //Should not happen
        }

        // setting public key
        try {
            SharedPreferences settings = super.getContext().getSharedPreferences(super.getDeviceId(), Context.MODE_PRIVATE);
            byte[] publicKeyBytes = Base64.decode(settings.getString("publicKey", ""), 0);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/Device","Exception");
        }

    }

    @Override
    public PairingHandler getNewPairingHandler(Device device) {
        return new LoopbackPairingHandler(getContext(), device);
    }


    @Override
    public void sendPackage(NetworkPackage in, Device.SendPackageStatusCallback callback) {
        sendPackageEncrypted(in, callback);
    }

    @Override
    public void sendPackageEncrypted(NetworkPackage in, Device.SendPackageStatusCallback callback) {
        try {
            if (publicKey != null) {
                in = encrypt(in);
            }
            String s = in.serialize();
            NetworkPackage out= NetworkPackage.unserialize(s);
            if (privateKey != null) {
                out = decrypt(out, privateKey);
            }
            packageReceived(out);
            if (in.hasPayload()) {
                callback.sendProgress(0);
                out.setPayload(in.getPayload(), in.getPayloadSize());
                callback.sendProgress(100);
            }
            callback.sendSuccess();
        } catch(Exception e) {
            callback.sendFailure(e);
        }
    }

    // Code copied from LanLink
    public NetworkPackage encrypt(NetworkPackage np) throws GeneralSecurityException {

        String serialized = np.serialize();

        int chunkSize = 128;

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        JSONArray chunks = new JSONArray();
        while (serialized.length() > 0) {
            if (serialized.length() < chunkSize) {
                chunkSize = serialized.length();
            }
            String chunk = serialized.substring(0, chunkSize);
            serialized = serialized.substring(chunkSize);
            byte[] chunkBytes = chunk.getBytes(Charset.defaultCharset());
            byte[] encryptedChunk;
            encryptedChunk = cipher.doFinal(chunkBytes);
            chunks.put(Base64.encodeToString(encryptedChunk, Base64.NO_WRAP));
        }

        //Log.i("NetworkPackage", "Encrypted " + chunks.length()+" chunks");

        NetworkPackage encrypted = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_ENCRYPTED);
        encrypted.set("data", chunks);
        encrypted.setPayload(np.getPayload(), np.getPayloadSize());
        return encrypted;

    }

    public NetworkPackage decrypt(NetworkPackage np, PrivateKey privateKey)  throws GeneralSecurityException, JSONException {

        JSONArray chunks = np.getJSONArray("data");

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        String decryptedJson = "";
        for (int i = 0; i < chunks.length(); i++) {
            byte[] encryptedChunk = Base64.decode(chunks.getString(i), Base64.NO_WRAP);
            String decryptedChunk = new String(cipher.doFinal(encryptedChunk));
            decryptedJson += decryptedChunk;
        }

        NetworkPackage decrypted = np.unserialize(decryptedJson);
        decrypted.setPayload(np.getPayload(), np.getPayloadSize());
        return decrypted;
    }
}
