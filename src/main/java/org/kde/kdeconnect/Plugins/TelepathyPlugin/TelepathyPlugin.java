package org.kde.kdeconnect.Plugins.TelepathyPlugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.UserInterface.DeviceActivity;
import org.kde.kdeconnect_tp.R;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.makeText;

public class TelepathyPlugin extends Plugin {

    static final String pluginPackage = "org.kde.kdeconnect.ktp";
    boolean hasPlugin;

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
    public boolean hasSettings() {
        return false;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean onCreate() {

        PackageManager mPm = context.getPackageManager();
        PackageInfo info = null;
        try {
            info = mPm.getPackageInfo(pluginPackage, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        hasPlugin = (info != null);

        return hasPlugin;
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public boolean onPackageReceived(NetworkPackage np) {
        if (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_TELEPATHY)) return false;

        Log.e("TelepathyPlugin",np.serialize());

        if (np.getBoolean("sendSms")) {
            String phoneNo = np.getString("receiver");
            String sms = np.getString("message");
            sendSms(phoneNo, sms);
        }

        if (np.getBoolean("requestContacts")) {

            ArrayList<String> vCards = new ArrayList<String>();

            Cursor cursor = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    null,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER + " > 0 ",
                    null,
                    null
            );

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
                    Log.e("Contacts","Size: "+vCards.size());
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


        if (np.getBoolean("requestNumbers")) {

            ArrayList<String> numbers = new ArrayList<String>();

            Cursor cursor = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    ContactsContract.Contacts.HAS_PHONE_NUMBER + " > 0 ",
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                try {
                    do {
                        String number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        numbers.add(number);
                    } while (cursor.moveToNext());
                    Log.e("Numbers","Size: "+numbers.size());
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("RequestContacts", e.getMessage());
                } finally {
                    cursor.close();
                }
            }

            NetworkPackage answer = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_TELEPATHY);
            answer.set("numbers",numbers);
            device.sendPackage(answer);

        }

        Log.e("TelepathyPlugin","Done");

        return true;
    }

    @Override
    public AlertDialog getErrorDialog(final Activity deviceActivity) {
        if (hasPlugin) return null;

        return new AlertDialog.Builder(deviceActivity)
            .setTitle("plugin needed")
            .setMessage("Extra plugin needed for being able to send sms")
            .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pluginPackage)));
                    } catch (android.content.ActivityNotFoundException anfe) {
                        //TODO: Play store not installed open F-Droid website here instead
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + pluginPackage)));
                    }
                    Intent intent = new Intent("");
                    deviceActivity.startActivityForResult(intent, DeviceActivity.RESULT_NEEDS_RELOAD);
                }
            })
            .setNegativeButton(R.string.cancel,new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    //Do nothing
                }
            })
            .create();

    }

    @Override
    public Button getInterfaceButton(Activity activity) {
        Button b = new Button(activity);
        b.setText("Test");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSms("679282399", "Test!");
            }
        });
        return b;
    }

    private void sendSms(String phoneNo, String sms) {
        if (!hasPlugin) Toast.makeText(context, "Plugin not installed!", Toast.LENGTH_LONG).show();
        Intent sendIntent = new Intent("org.kde.kdeconnect.ktp");
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.setPackage(pluginPackage);
        sendIntent.putExtra(Intent.EXTRA_TEXT, phoneNo + "\n" + sms);
        sendIntent.setType("kdeconnect/sms");
        sendIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(sendIntent);
    }
}
