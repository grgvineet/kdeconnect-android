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

import android.app.ActionBar;
import android.app.ListActivity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.Plugins.MprisPlugin.MprisPlugin;
import org.kde.kdeconnect.UserInterface.List.ListAdapter;
import org.kde.kdeconnect_tp.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class RunCommandActivity extends ActionBarActivity {

    String deviceId;
    String[] commandKeys;
    String[] commandNames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        android.support.v7.app.ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayOptions(android.support.v7.app.ActionBar.DISPLAY_SHOW_HOME | android.support.v7.app.ActionBar.DISPLAY_SHOW_TITLE | android.support.v7.app.ActionBar.DISPLAY_SHOW_CUSTOM);

        deviceId = getIntent().getStringExtra("deviceId");

        //THE CALLBACK HELL
        BackgroundService.RunCommand(this, new BackgroundService.InstanceCallback() {
            @Override
            public void onServiceStart(BackgroundService service) {
                Device device = service.getDevice(deviceId);
                RunCommandPlugin runcommand = (RunCommandPlugin) device.getPlugin("plugin_runcommand");
                runcommand.setRefreshHandler(new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        BackgroundService.RunCommand(RunCommandActivity.this, new BackgroundService.InstanceCallback() {
                            @Override
                            public void onServiceStart(BackgroundService service) {

                                Log.e("REFRESH","REFRESH");

                                Device device = service.getDevice(deviceId);
                                RunCommandPlugin runcommand = (RunCommandPlugin) device.getPlugin("plugin_runcommand");

                                Map<String, String> commands = runcommand.getCommands();
                                commandKeys = commands.keySet().toArray(new String[0]);
                                commandNames = commands.values().toArray(new String[0]);

                                final ArrayAdapter<String> adapter = new ArrayAdapter<String>(RunCommandActivity.this,
                                        R.layout.list_item_entry, R.id.list_item_entry_title, commandNames);

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        ListView list = (ListView)findViewById(R.id.listView1);
                                        list.setAdapter(adapter);
                                        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                                            @Override
                                            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                                                if (commandKeys.length == 0) return;
                                                BackgroundService.RunCommand(RunCommandActivity.this, new BackgroundService.InstanceCallback() {
                                                    @Override
                                                    public void onServiceStart(BackgroundService service) {
                                                        Device device = service.getDevice(deviceId);
                                                        RunCommandPlugin runcommand = (RunCommandPlugin) device.getPlugin("plugin_runcommand");
                                                        runcommand.sendCommand(commandKeys[position]);
                                                    }
                                                });
                                            }
                                        });
                                    }
                                });

                            }
                        });
                    }
                });
            }
        });
    }

}