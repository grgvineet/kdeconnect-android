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

package org.kde.kdeconnect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.LanBackend.LanPairingHandler;
import org.kde.kdeconnect.Backends.PairingHandler;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.Plugins.PluginFactory;
import org.kde.kdeconnect_tp.R;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public class Device implements BaseLink.PackageReceiver {

    private final Context context;

    private final String deviceId;
    private String name;
    private String type;

    private int protocolVersion;
    private PairingHandler pairingHandler;

    private PairingHandler.PairStatus pairStatus;

    private final ArrayList<BaseLink> links = new ArrayList<BaseLink>();
    private final HashMap<String, Plugin> plugins = new HashMap<String, Plugin>();
    private final HashMap<String, Plugin> failedPlugins = new HashMap<String, Plugin>();

    private final SharedPreferences settings;


    //Remembered trusted device, we need to wait for a incoming devicelink to communicate
    Device(Context context, String deviceId) {
        settings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);

        //Log.e("Device","Constructor A");

        this.context = context;
        this.deviceId = deviceId;
        this.name = settings.getString("deviceName", context.getString(R.string.unknown_device));
        this.type = settings.getString("type", "normal");
        this.pairStatus = PairingHandler.PairStatus.Paired;
        this.protocolVersion = NetworkPackage.ProtocolVersion; //We don't know it yet

        reloadPluginsFromSettings();
    }

    //Device known via an incoming connection sent to us via a devicelink, we know everything but we don't trust it yet
    Device(Context context, NetworkPackage np, BaseLink dl) {

        //Log.e("Device","Constructor B");

        this.context = context;
        this.deviceId = np.getString("deviceId");
        this.name = np.getString("deviceName", context.getString(R.string.unknown_device));
        this.protocolVersion = np.getInt("protocolVersion");
        this.type = np.getString("type", "normal");
        this.pairStatus = PairingHandler.PairStatus.NotPaired;

        settings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);

        addLink(np, dl);
    }

    public String getName() {
        return name != null? name : context.getString(R.string.unknown_device);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getType() {
        return type;
    }

    public PairingHandler.PairStatus getPairStatus() {
        return pairStatus;
    }

    public void setPairStatus (PairingHandler.PairStatus pairStatus) {
        this.pairStatus = pairStatus;
    }

    public PairingHandler getPairingHandler() {
        return pairingHandler;
    }

    public void setPairingHandler(PairingHandler pairingHandler) {
        this.pairingHandler = pairingHandler;
    }

    public PublicKey getPublicKey() {
        try {
            SharedPreferences settings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);
            byte[] publicKeyBytes = Base64.decode(settings.getString("publicKey", ""), 0);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            return publicKey;
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/Device","Exception");
        }
        return null;
    }

    //Returns 0 if the version matches, < 0 if it is older or > 0 if it is newer
    public int compareProtocolVersion() {
        return protocolVersion - NetworkPackage.ProtocolVersion;
    }





    //
    // Pairing-related functions
    //

    public boolean isPaired() {
        return pairStatus == PairingHandler.PairStatus.Paired;
    }

    public boolean isPairRequested() {
        return pairStatus == PairingHandler.PairStatus.Requested;
    }

    public void addPairingCallback(PairingHandler.PairingCallback callback) {
        pairingHandler.addPairingCallback(callback);
    }
    public void removePairingCallback(PairingHandler.PairingCallback callback) {
        pairingHandler.removePairingCallback(callback);
    }

    public void requestPairing() {
        pairingHandler.requestPairing();
    }

    public void unpair() {
        pairingHandler.unpair();
    }

    public void acceptPairing() {
        pairingHandler.acceptPairing();
    }

    public void rejectPairing() {
        pairingHandler.rejectPairing();
    }




    //
    // ComputerLink-related functions
    //

    public boolean isReachable() {
        return !links.isEmpty();
    }

    public void addLink(NetworkPackage identityPackage, BaseLink link) {
        //FilesHelper.LogOpenFileCount();

        this.protocolVersion = identityPackage.getInt("protocolVersion");

        if (identityPackage.has("deviceName")) {
            this.name = identityPackage.getString("deviceName", this.name);
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("deviceName", this.name);
            editor.apply();
        }


        links.add(link);

        Log.i("KDE/Device","addLink "+link.getLinkProvider().getName()+" -> "+getName() + " active links: "+ links.size());

        /*
        Collections.sort(links, new Comparator<BaseLink>() {
            @Override
            public int compare(BaseLink o, BaseLink o2) {
                return o2.getLinkProvider().getPriority() - o.getLinkProvider().getPriority();
            }
        });
        */

        link.addPackageReceiver(this);

        if (links.size() == 1) {
            reloadPluginsFromSettings();
        }
    }

    public void removeLink(BaseLink link) {
        //FilesHelper.LogOpenFileCount();

        link.removePackageReceiver(this);
        links.remove(link);
        Log.i("KDE/Device", "removeLink: " + link.getLinkProvider().getName() + " -> " + getName() + " active links: " + links.size());
        if (links.isEmpty()) {
            reloadPluginsFromSettings();
        }
    }

    public void initialiseLinks() {
        for (BaseLink link : links) {
            link.initialiseLink();
        }
    }

    @Override
    public void onPackageReceived(NetworkPackage np) {

        if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_PAIR)) {
            pairingHandler.handlePairingPackage(np);
        } else if (isPaired()) {

            for (Plugin plugin : plugins.values()) {
                try {
                    plugin.onPackageReceived(np);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("KDE/Device", "Exception in "+plugin.getDisplayName()+"'s onPackageReceived()");
                }

            }

        } else {

            Log.e("KDE/onPackageReceived","Device not paired, ignoring package!");

            if (pairStatus != PairingHandler.PairStatus.Requested) {
                unpair();
            }

        }

    }

    public static abstract class SendPackageStatusCallback {
        protected abstract void onSuccess();
        protected abstract void onFailure(Throwable e);
        protected void onProgressChanged(int percent) { }

        private boolean success = false;
        public void sendSuccess() {
            success = true;
            onSuccess();
        }
        public void sendFailure(Throwable e) {
            if (e != null) {
                e.printStackTrace();
                Log.e("KDE/sendPackage", "Exception: " + e.getMessage());
            } else {
                Log.e("KDE/sendPackage", "Unknown (null) exception");
            }
            onFailure(e);
        }
        public void sendProgress(int percent) { onProgressChanged(percent); }
    }


    public void sendPackage(NetworkPackage np) {
        sendPackage(np,new SendPackageStatusCallback() {
            @Override
            protected void onSuccess() { }
            @Override
            protected void onFailure(Throwable e) { }
        });
    }

    //Async
    public void sendPackage(final NetworkPackage np, final SendPackageStatusCallback callback) {

        //Log.e("sendPackage", "Sending package...");
        //Log.e("sendPackage", np.serialize());

        final Throwable backtrace = new Throwable();
        new Thread(new Runnable() {
            @Override
            public void run() {

                boolean useEncryption = (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_PAIR) && isPaired());

                //Make a copy to avoid concurrent modification exception if the original list changes
                ArrayList<BaseLink> mLinks = new ArrayList<BaseLink>(links);
                for (final BaseLink link : mLinks) {
                    if (link == null) continue; //Since we made a copy, maybe somebody destroyed the link in the meanwhile
                    if (useEncryption) {
                        link.sendPackageEncrypted(np, callback);
                    } else {
                        link.sendPackage(np, callback);
                    }
                    if (callback.success) break; //If the link didn't call sendSuccess(), try the next one
                }

                if (!callback.success) {
                    Log.e("KDE/sendPackage", "No device link (of "+mLinks.size()+" available) could send the package. Package lost!");
                    backtrace.printStackTrace();
                }

            }
        }).start();

    }

    //
    // Plugin-related functions
    //

    public Plugin getPlugin(String name) {
        return getPlugin(name, false);
    }

    public Plugin getPlugin(String name, boolean includeFailed) {
        Plugin plugin = plugins.get(name);
        if (includeFailed && plugin == null) {
            plugin = failedPlugins.get(name);
        }
        return plugin;
    }

    private synchronized void addPlugin(final String name) {
        Plugin existing = plugins.get(name);
        if (existing != null) {
            Log.w("KDE/addPlugin","plugin already present:" + name);
            return;
        }

        final Plugin plugin = PluginFactory.instantiatePluginForDevice(context, name, this);
        if (plugin == null) {
            Log.e("KDE/addPlugin","could not instantiate plugin: "+name);
            failedPlugins.put(name, plugin);
            return;
        }

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {

                boolean success;
                try {
                    success = plugin.onCreate();
                } catch (Exception e) {
                    success = false;
                    e.printStackTrace();
                    Log.e("KDE/addPlugin", "Exception loading plugin " + name);
                }

                if (success) {
                    //Log.e("addPlugin","added " + name);
                    failedPlugins.remove(name);
                    plugins.put(name, plugin);
                } else {
                    Log.e("KDE/addPlugin", "plugin failed to load " + name);
                    plugins.remove(name);
                    failedPlugins.put(name, plugin);
                }

                for (PluginsChangedListener listener : pluginsChangedListeners) {
                    listener.onPluginsChanged(Device.this);
                }

            }
        });

    }

    private synchronized boolean removePlugin(String name) {

        Plugin plugin = plugins.remove(name);
        Plugin failedPlugin = failedPlugins.remove(name);

        if (plugin == null) {
            if (failedPlugin == null) {
                //Not found
                return false;
            }
            plugin = failedPlugin;
        }

        try {
            plugin.onDestroy();
            //Log.e("removePlugin","removed " + name);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/removePlugin","Exception calling onDestroy for plugin "+name);
        }

        for (PluginsChangedListener listener : pluginsChangedListeners) {
            listener.onPluginsChanged(this);
        }

        return true;
    }

    public void setPluginEnabled(String pluginName, boolean value) {
        settings.edit().putBoolean(pluginName,value).apply();
        if (value && isPaired() && isReachable()) addPlugin(pluginName);
        else removePlugin(pluginName);
    }

    public boolean isPluginEnabled(String pluginName) {
        boolean enabledByDefault = PluginFactory.getPluginInfo(context, pluginName).isEnabledByDefault();
        boolean enabled = settings.getBoolean(pluginName, enabledByDefault);
        return enabled;
    }

    public boolean hasPluginsLoaded() {
        return !plugins.isEmpty() || !failedPlugins.isEmpty();
    }

    public void reloadPluginsFromSettings() {

        failedPlugins.clear();

        Set<String> availablePlugins = PluginFactory.getAvailablePlugins();

        for(String pluginName : availablePlugins) {
            boolean enabled = false;
            if (isPaired() && isReachable()) {
                enabled = isPluginEnabled(pluginName);
            }
            if (enabled) {
                addPlugin(pluginName);
            } else {
                removePlugin(pluginName);
            }
        }

        //No need to call PluginsChangedListeners because addPlugin and removePlugin already do so
    }

    public HashMap<String,Plugin> getLoadedPlugins() {
        return plugins;
    }

    public HashMap<String,Plugin> getFailedPlugins() {
        return failedPlugins;
    }

    public interface PluginsChangedListener {
        void onPluginsChanged(Device device);
    }

    private final ArrayList<PluginsChangedListener> pluginsChangedListeners = new ArrayList<PluginsChangedListener>();

    public void addPluginsChangedListener(PluginsChangedListener listener) {
        pluginsChangedListeners.add(listener);
    }

    public void removePluginsChangedListener(PluginsChangedListener listener) {
        pluginsChangedListeners.remove(listener);
    }

}
