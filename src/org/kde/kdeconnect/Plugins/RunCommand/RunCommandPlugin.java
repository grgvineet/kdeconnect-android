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

package org.kde.kdeconnect.Plugins.RunCommand;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.Plugins.MprisPlugin.MprisActivity;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.UserInterface.MainActivity;
import org.kde.kdeconnect_tp.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;


public class RunCommandPlugin extends Plugin {

    private Map<String, String> commands = new TreeMap<String, String>();
    Handler refreshHandler = null;

    @Override
    public String getPluginName() {
        return "plugin_runcommand";
    }

    @Override
    public String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_runcommand);
    }

    @Override
    public String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_runcommand_desc);
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
        return true;
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public boolean onPackageReceived(NetworkPackage np) {
        Log.e("TYPE", np.getType());
        if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_RUNCOMMAND)) {
            ArrayList<String> keys = np.getStringList("keys");
            ArrayList<String> names = np.getStringList("names");
            Iterator<String> i1 = keys.iterator();
            Iterator<String> i2 = names.iterator();
            while (i1.hasNext() && i2.hasNext()) {
                commands.put(i1.next(), i2.next());
            }

            if (refreshHandler != null) {
                refreshHandler.dispatchMessage(new Message());
            }

        }
        return false;
    }

    public void setRefreshHandler(Handler h) {
        refreshHandler = h;
        if (!commands.isEmpty()) {
            h.dispatchMessage(new Message());
        } else {
            NetworkPackage np = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_RUNCOMMAND);
            np.set("ask",true);
            device.sendPackage(np);
        }
    }

    @Override
    public AlertDialog getErrorDialog(Activity deviceActivity) {
        return null;
    }

    @Override
    public Button getInterfaceButton(final Activity activity) {
        Button b = new Button(activity);
        b.setText(R.string.open_mpris_controls);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(activity, RunCommandActivity.class);
                intent.putExtra("deviceId", device.getDeviceId());
                activity.startActivity(intent);
            }
        });
        return b;
    }


    public Map<String, String> getCommands() {
        return commands;
    }

    public void sendCommand(String key) {
        NetworkPackage np = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_RUNCOMMAND);
        np.set("key",key);
        device.sendPackage(np);
    }


}
