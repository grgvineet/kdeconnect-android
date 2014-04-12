package org.kde.kdeconnect.Plugins.TelepathyPlugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Button;

import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect_tp.R;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;

public class TelepathyPlugin extends Plugin {

    /*static {
        PluginFactory.registerPlugin(TelephonyPlugin.class);
    }*/

    @Override
    public String getPluginName() {
        return "plugin_telepathy";
    }

    @Override
    public String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_telepathy);
    }

    @Override
    public String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_telepathy_desc);
    }

    @Override
    public Drawable getIcon() {
        return context.getResources().getDrawable(R.drawable.icon);
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public boolean onPackageReceived(NetworkPackage np) {
        if (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_TELEPATHY)) return false;

        if (np.getBoolean("requestContacts")) {

            ArrayList<String> vCards = new ArrayList<String>();

            Cursor cursor = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                try {
                    do {
                        String lookupKey = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
                        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey);
                        AssetFileDescriptor fd = null;
                        try {

                            fd = context.getContentResolver()
                                    .openAssetFileDescriptor(uri, "r");
                            FileInputStream fis = fd.createInputStream();
                            byte[] b = new byte[(int) fd.getDeclaredLength()];
                            fis.read(b);
                            String vCard = new String(b);

                            vCards.add(vCard);

                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                            Log.e("RequestContacts-Contact-FileNotFound", e.getMessage());
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e("RequestContacts-Contact", e.getMessage());
                        } finally {
                            if (fd != null) fd.close();
                        }
                    } while (cursor.moveToNext());
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("RequestContacts", e.getMessage());
                } finally {
                    cursor.close();
                }
            }

            NetworkPackage answer = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_TELEPATHY);
            answer.set("contacts",vCards);
            device.sendPackage(answer);

        }

        return true;
    }

    @Override
    public AlertDialog getErrorDialog(Context baseContext) {
        return null;
    }

    @Override
    public Button getInterfaceButton(Activity activity) {
        return null;
    }
}
