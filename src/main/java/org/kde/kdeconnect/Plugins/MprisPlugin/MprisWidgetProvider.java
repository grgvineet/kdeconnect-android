/**
 * Copyright 2014 Valentin Rusu <kde@rusu.info>
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
package org.kde.kdeconnect.Plugins.MprisPlugin;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect_tp.BuildConfig;
import org.kde.kdeconnect_tp.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 * This widget will display mpris status. We assume user will always listen to one mpris-enabled
 * application at a time. So this widget will display the current player status
 *
 * TODO: click kdeconnect logo to change volume
 */
public class MprisWidgetProvider extends AppWidgetProvider {
    private static final String LOG_TAG = MprisWidgetProvider.class.getName();

    private static final String WIDGET_CREATE = "org.kde.kdeconnect.Plugins.MprisPlugin.WIDGET_CREATE";
    public static final String WIDGET_REQUEST_PLAY = "org.kde.kdeconnect.Plugins.MprisPlugin.WIDGET_PLAY";
    public static final String WIDGET_REQUEST_NEXT = "org.kde.kdeconnect.Plugins.MprisPlugin.WIDGET_NEXT";
    private static final String WIDGET_REQUEST_PREV = "org.kde.kdeconnect.Plugins.MprisPlugin.WIDGET_PREV";
    private static final String WIDGET_DELETED = "org.kde.kdeconnect.Plugins.MprisPlugin.WIDGET_DELETED";
    public static final String EXTRA_WIDGET_ID = "org.kde.kdeconnect.Plugins.MprisPlugin.EXTRA_WIDGET_ID";

    public static class UpdateService extends Service {
        private static String LOG_TAG = UpdateService.class.getName();

        private ArrayList<Integer> knownWidgets = new ArrayList<Integer>();
        private Collection<Device> devices;
        private MprisPlugin activePlugin = null;

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.d(LOG_TAG, "OnStartCommand");

            if (intent.getAction().equals(WIDGET_CREATE)) {
                if (devices == null) {
                    initialize();
                }
                Integer appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
                Log.d(LOG_TAG, "creating view for widgetId=".concat(appWidgetId.toString()));
                if (BuildConfig.DEBUG && (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID)) throw new AssertionError("Forgot setting the appWidgetId extra?");
                knownWidgets.add(appWidgetId);
                updateViews();
            } else
            if (intent.getAction().equals(WIDGET_DELETED)) {
                int[] ids = intent.getIntArrayExtra(EXTRA_WIDGET_ID);
                for (Integer theId : ids) {
                    knownWidgets.remove(theId);
                    if (knownWidgets.isEmpty()) {
                        Log.d(LOG_TAG, "Removing last view, so resetting update service");
                        devices = null;
                        activePlugin = null;
                        stopSelf();
                    }
                }
            } else
            if (intent.getAction().equals(WIDGET_REQUEST_NEXT)) {
                Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
                Log.d(LOG_TAG, "WIDGET_REQUEST_NEXT ~ widgetId : ".concat(widgetId.toString()));
                if (activePlugin != null)
                    activePlugin.sendAction(MprisPlugin.ACTION_NEXT);
            } else
            if (intent.getAction().equals(WIDGET_REQUEST_PLAY)) {
                Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
                Log.d(LOG_TAG, "WIDGET_REQUEST_PLAY ~ widgetId : ".concat(widgetId.toString()));
                if (activePlugin != null)
                    activePlugin.sendAction(MprisPlugin.ACTION_PLAY_PAUSE);
            } else
            if (intent.getAction().equals(WIDGET_REQUEST_PREV)) {
                Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
                Log.d(LOG_TAG, "WIDGET_REQUEST_PREV ~ widgetId : ".concat(widgetId.toString()));
                if (activePlugin != null)
                    activePlugin.sendAction(MprisPlugin.ACTION_PREV);
            }

            return START_STICKY;
        }

        private void initialize() {
            BackgroundService.RunCommand(this, new BackgroundService.InstanceCallback() {
                @Override
                public void onServiceStart(final BackgroundService service) {
                    attachMprisPlugins(service);
                    service.setDeviceListChangedCallback(new BackgroundService.DeviceListChangedCallback() {
                        @Override
                        public void onDeviceListChanged() {
                            attachMprisPlugins(service);
                        }
                    });
                }
            });
        }

        /**
         * The kdeconnect communications layer, in BackgroundService, send
         * notifications only to active devices and plug-ins. So here we get
         * some references to all the devices and so, we'll receive the
         * notifications from the mpris plugins, if they're enabled on the paired devices
         *
         * @param service
         */
        private void attachMprisPlugins(BackgroundService service) {
            Log.d(LOG_TAG, "attachMprisPlugins");
            devices = service.getDevices().values();
            for (Device device : devices) {
                Log.d(LOG_TAG, "found paired device : ".concat(device.getName()));
                final MprisPlugin plugin = (MprisPlugin) device.getPlugin(MprisPlugin.PLUGIN_MPRIS_NAME);
                if (plugin != null) {
                    plugin.setPlayerListUpdatedHandler(new Handler() {
                        @Override
                        public void dispatchMessage(Message msg) {
                            onPlayerListUpdated(plugin);
                        }
                    });
                } else {
                    Log.d(LOG_TAG, "device does not have a mpris plugin");
                }
            }
        }

        private void onPlayerListUpdated(final MprisPlugin plugin) {
            Log.d(LOG_TAG, "onPlayerListUpdated");
            ArrayList<String> playerList = plugin.getPlayerList();
            final Iterator<String> playerIterator = playerList.iterator();
            if (playerIterator.hasNext()) {
                tryNextPlayer(plugin, playerIterator);
            } else {
                Log.d(LOG_TAG, "no player found on the current device");
                onPlayerStatusUpdated(null);
            }
        }

        private void tryNextPlayer(final MprisPlugin plugin, final Iterator<String> playerIterator) {
            String player = playerIterator.next();
            Log.d(LOG_TAG, "trying player ".concat(player));

            // this will trigger the update handler below, which will eventually
            // continue the iteration if the player is inactive
            plugin.setPlayer(player);

            final Handler onPlayerStatusUpdatedHandler = new Handler(){
                @Override
                public void dispatchMessage(Message msg) {
                    onPlayerStatusUpdated(plugin);
                }
            };

            plugin.setPlayerStatusUpdatedHandler(new Handler(){
                /**
                 * NOTE this update handler will get called from a system thread and not from
                 * our thread. To avoid a RuntimeException "Can't create handler inside thread
                 * that has not called Looper.prepare()", we'll instantiation the Handler beforehand
                 * @param msg
                 */
                @Override
                public void dispatchMessage(Message msg){
                    if (plugin.isPlaying()) {
                        Log.d(LOG_TAG, "found active player : ".concat(plugin.getPlayer()));
                        onPlayerStatusUpdated(plugin);
                        plugin.setPlayerStatusUpdatedHandler(onPlayerStatusUpdatedHandler);
                    } else {
                        if (playerIterator.hasNext()) {
                            tryNextPlayer(plugin, playerIterator);
                        } else {
                            Log.d(LOG_TAG, "finished iterating over players, no player found.");
                        }
                    }
                }
            });
        }

        private void onPlayerStatusUpdated(MprisPlugin plugin) {
            Log.d(LOG_TAG, "onPlayerStatusUpdated");
            activePlugin = plugin;
            updateViews();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            Log.d(LOG_TAG, "onDestroy");
        }

        private void updateViews() {
            RemoteViews views = buildView();
            ComponentName componentName = new ComponentName(this, MprisWidgetProvider.class);
            AppWidgetManager.getInstance(this).updateAppWidget(componentName, views);
        }

        public RemoteViews buildView() {
            Log.d(LOG_TAG, "buildView");

            RemoteViews views = new RemoteViews(this.getPackageName(), R.layout.mpris_widget);
            final String playerInfo;
            if (activePlugin != null) {
                String playerInfoFormat = getResources().getString(R.string.mpris_widget_info_format);
                playerInfo = String.format(
                        playerInfoFormat,
                        activePlugin.getPlayer(),
                        activePlugin.getDevice().getName(),
                        activePlugin.getCurrentSong());
            } else {
                playerInfo = getResources().getString(R.string.mpris_widget_info_waiting);
            }

            views.setTextViewText(R.id.player_info, Html.fromHtml(playerInfo));

            // configure the buttons
            if (activePlugin != null) {
                final Intent onPlayIntent = new Intent(this, MprisWidgetProvider.class);
                onPlayIntent.setAction(MprisWidgetProvider.WIDGET_REQUEST_PLAY);
                final PendingIntent mediaPlayIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        onPlayIntent,
                        0
                );
                views.setOnClickPendingIntent(R.id.play_button, mediaPlayIntent);

                final Intent onNextIntent = new Intent(this, MprisWidgetProvider.class);
                onNextIntent.setAction(MprisWidgetProvider.WIDGET_REQUEST_NEXT);
                PendingIntent mediaNextIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        onNextIntent,
                        0
                );
                views.setOnClickPendingIntent(R.id.next_button, mediaNextIntent);

                final Intent onPrevIntent = new Intent(this, MprisWidgetProvider.class);
                onPrevIntent.setAction(MprisWidgetProvider.WIDGET_REQUEST_PREV);
                PendingIntent mediaPrevIntent = PendingIntent.getBroadcast(
                        this, 0, onPrevIntent, 0);
                views.setOnClickPendingIntent(R.id.prev_button, mediaPrevIntent);

                if ((activePlugin != null) && (activePlugin.isPlaying())) {
                    views.setImageViewResource(R.id.play_button, android.R.drawable.ic_media_pause);
                } else {
                    views.setImageViewResource(R.id.play_button, android.R.drawable.ic_media_play);
                }

                views.setViewVisibility(R.id.play_button, View.VISIBLE);
                views.setViewVisibility(R.id.next_button, View.VISIBLE);
                views.setViewVisibility(R.id.prev_button, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.play_button, View.INVISIBLE);
                views.setViewVisibility(R.id.next_button, View.INVISIBLE);
                views.setViewVisibility(R.id.prev_button, View.INVISIBLE);
            }
            return views;
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        Log.d(LOG_TAG, "onUpdate");

        final int N = appWidgetIds.length;
        for (Integer appWidgetId : appWidgetIds) {
            Log.d(LOG_TAG, "onUpdate for widgetId=".concat(appWidgetId.toString()));
            Intent buildViewIntent = new Intent(context, UpdateService.class);
            buildViewIntent.setAction(WIDGET_CREATE);
            buildViewIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            context.startService(buildViewIntent);
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        Log.d(LOG_TAG, "onDeleted");
        super.onDeleted(context, ids);
        Intent onDeletedIntent = new Intent(context, UpdateService.class);
        onDeletedIntent.setAction(WIDGET_DELETED);
        onDeletedIntent.putExtra(EXTRA_WIDGET_ID, ids);
        context.startService(onDeletedIntent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(LOG_TAG, "onReceive");
        final String action = intent.getAction();
        if (action != null) {
            if (action.equals(WIDGET_REQUEST_PLAY)) {
                Log.d(LOG_TAG, "onReceive : WIDGET_REQUEST_PLAY");
                onPlayClicked(context, intent);
            } else
            if (action.equals(WIDGET_REQUEST_NEXT)) {
                Log.d(LOG_TAG, "onReceive : WIDGET_REQUEST_PLAY");
                onNextClicked(context, intent);
            } else
            if (action.equals(WIDGET_REQUEST_PREV)) {
                Log.d(LOG_TAG, "onReceive: WIDGET_REQUEST_PREV");
                onPrevClicked(context, intent);
            }
        }

        super.onReceive(context, intent);
    }

    private void onNextClicked(Context context, Intent intent) {
        intent.setClass(context, UpdateService.class);
        Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, 0);
        Log.d(LOG_TAG, "widgetId=".concat(widgetId.toString()));
        context.startService(intent);
    }

    private void onPlayClicked(Context context, Intent intent) {
        intent.setClass(context, UpdateService.class);
        Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, 0);
        Log.d(LOG_TAG, "widgetId=".concat(widgetId.toString()));
        context.startService(intent);
    }

    private void onPrevClicked(Context context, Intent intent) {
        intent.setClass(context, UpdateService.class);
        Integer widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, 0);
        Log.d(LOG_TAG, "widgetId=".concat(widgetId.toString()));
        context.startService(intent);
    }
}
