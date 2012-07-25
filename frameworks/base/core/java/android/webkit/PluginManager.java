/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Class for managing the relationship between the {@link WebView} and installed
 * plugins in the system. You can find this class through
 * {@link PluginManager#getInstance}.
 * 
 * @hide pending API solidification
 */
public class PluginManager {

    /**
     * Service Action: A plugin wishes to be loaded in the WebView must provide
     * {@link android.content.IntentFilter IntentFilter} that accepts this
     * action in their AndroidManifest.xml.
     * <p>
     * TODO: we may change this to a new PLUGIN_ACTION if this is going to be
     * public.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String PLUGIN_ACTION = "android.webkit.PLUGIN";

    /**
     * A plugin wishes to be loaded in the WebView must provide this permission
     * in their AndroidManifest.xml.
     */
    public static final String PLUGIN_PERMISSION = "android.webkit.permission.PLUGIN";

    private static final String LOGTAG = "PluginManager";

    private static final String PLUGIN_SYSTEM_LIB = "/system/lib/plugins/";

    private static final String PLUGIN_TYPE = "type";
    private static final String TYPE_NATIVE = "native";

    private static PluginManager mInstance = null;

    private final Context mContext;

    private ArrayList<PackageInfo> mPackageInfoCache;

    // Only plugin matches one of the signatures in the list can be loaded
    // inside the WebView process
    private static final String SIGNATURE_1 = "308204c5308203ada003020102020900d7cb412f75f4887e300d06092a864886f70d010105050030819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f7261746564301e170d3039313030313030323331345a170d3337303231363030323331345a30819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f726174656430820120300d06092a864886f70d01010105000382010d0030820108028201010099724f3e05bbd78843794f357776e04b340e13cb1c9ccb3044865180d7d8fec8166c5bbd876da8b80aa71eb6ba3d4d3455c9a8de162d24a25c4c1cd04c9523affd06a279fc8f0d018f242486bdbb2dbfbf6fcb21ed567879091928b876f7ccebc7bccef157366ebe74e33ae1d7e9373091adab8327482154afc0693a549522f8c796dd84d16e24bb221f5dbb809ca56dd2b6e799c5fa06b6d9c5c09ada54ea4c5db1523a9794ed22a3889e5e05b29f8ee0a8d61efe07ae28f65dece2ff7edc5b1416d7c7aad7f0d35e8f4a4b964dbf50ae9aa6d620157770d974131b3e7e3abd6d163d65758e2f0822db9c88598b9db6263d963d13942c91fc5efe34fc1e06e3020103a382010630820102301d0603551d0e041604145af418e419a639e1657db960996364a37ef20d403081d20603551d230481ca3081c780145af418e419a639e1657db960996364a37ef20d40a181a3a481a030819d310b3009060355040613025553311330110603550408130a43616c69666f726e69613111300f0603550407130853616e204a6f736531233021060355040a131a41646f62652053797374656d7320496e636f72706f7261746564311c301a060355040b1313496e666f726d6174696f6e2053797374656d73312330210603550403131a41646f62652053797374656d7320496e636f72706f7261746564820900d7cb412f75f4887e300c0603551d13040530030101ff300d06092a864886f70d0101050500038201010076c2a11fe303359689c2ebc7b2c398eff8c3f9ad545cdbac75df63bf7b5395b6988d1842d6aa1556d595b5692e08224d667a4c9c438f05e74906c53dd8016dde7004068866f01846365efd146e9bfaa48c9ecf657f87b97c757da11f225c4a24177bf2d7188e6cce2a70a1e8a841a14471eb51457398b8a0addd8b6c8c1538ca8f1e40b4d8b960009ea22c188d28924813d2c0b4a4d334b7cf05507e1fcf0a06fe946c7ffc435e173af6fc3e3400643710acc806f830a14788291d46f2feed9fb5c70423ca747ed1572d752894ac1f19f93989766308579393fabb43649aa8806a313b1ab9a50922a44c2467b9062037f2da0d484d9ffd8fe628eeea629ba637";
    private static final String SIGNATURE_FSL = "3082035a30820242a00302010202044da29ea8300d06092a864886f70d0101050500306f310b300906035504061302434e3111300f060355040813085368616e676861693111300f060355040713085368616e6768616931123010060355040a1309467265657363616c6531123010060355040b1309467265657363616c653112301006035504031309467265657363616c65301e170d3131303431313036323434305a170d3338303832373036323434305a306f310b300906035504061302434e3111300f060355040813085368616e676861693111300f060355040713085368616e6768616931123010060355040a1309467265657363616c6531123010060355040b1309467265657363616c653112301006035504031309467265657363616c6530820122300d06092a864886f70d01010105000382010f003082010a0282010100aad4a6a86acf456796da8b5242b119f5eee6b3cef40d05521d896df2e06c079dedaf28fb386cf6cccf77116ab19db364117b66655c3cb0edb09ed8edb334643bfaa2bf722522fce5205a1dca14afa83fd3969f1910ddd765984772d2b38ab0209f6e3b94dd367c08422b1a2583d212bc49e0faa634db9b86c1851c575bf810f3c81bdbe1a879f1573cf683a1afba0de677f9d98d0cf58557340d7f77a1c7f1ee8268b4b75d32b78cd2616c7707662150d4f7da1e3e3bfc4832fd57bcd9686b9a8eea68d86416fbd8b6366ff8b3d2cc1ba979683233148c50fc2d6169830bb22ec0da3be75bfc2314ee11a5191ffe67eab76ea84098ffd13cc8a32d13e8c99bb10203010001300d06092a864886f70d01010505000382010100291932f92c046aba065939e46fba73e97a8a6657d580cc843db23c45b334d914ca285f88c3a200df85b1aa67cefdebec14fb12b52ef4164a65e8a5682e6174bebfe611cdc4851b8af0e80d853e9d2c9ba426719ab9b17d898f014a97005abb36e4675ed2c884427d11bbd17fecb3f181e6ba855a5adb1a6beb884ca02f7ffd01b82a3a52597b0ca8c0aba005e6b20cfe2cb738484413c6178998368ecaf261445e5ed30ec406b831be81f8684497a77dae9ad074a4913da0fbfea3e29b1efad0b1f46b1e28f1b2193d651b81e20549ecb2ea74734b0577a2a9ed5e32c31ec1ddf15b75b3ea001d25fc1124defb06f03fc04f1c6fe4f5ea8b3d5cf3a31c54ee2b";

    private static final Signature[] SIGNATURES = new Signature[] {
        new Signature(SIGNATURE_1),
	new Signature(SIGNATURE_FSL),
    };

    private PluginManager(Context context) {
        mContext = context;
        mPackageInfoCache = new ArrayList<PackageInfo>();
    }

    public static synchronized PluginManager getInstance(Context context) {
        if (mInstance == null) {
            if (context == null) {
                throw new IllegalStateException(
                        "First call to PluginManager need a valid context.");
            }
            mInstance = new PluginManager(context.getApplicationContext());
        }
        return mInstance;
    }

    /**
     * Signal the WebCore thread to refresh its list of plugins. Use this if the
     * directory contents of one of the plugin directories has been modified and
     * needs its changes reflecting. May cause plugin load and/or unload.
     * 
     * @param reloadOpenPages Set to true to reload all open pages.
     */
    public void refreshPlugins(boolean reloadOpenPages) {
        BrowserFrame.sJavaBridge.obtainMessage(
                JWebCoreJavaBridge.REFRESH_PLUGINS, reloadOpenPages)
                .sendToTarget();
    }

    String[] getPluginDirectories() {

        ArrayList<String> directories = new ArrayList<String>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> plugins = pm.queryIntentServices(new Intent(PLUGIN_ACTION),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);

        synchronized(mPackageInfoCache) {

            // clear the list of existing packageInfo objects
            mPackageInfoCache.clear();

            for (ResolveInfo info : plugins) {

                // retrieve the plugin's service information
                ServiceInfo serviceInfo = info.serviceInfo;
                if (serviceInfo == null) {
                    Log.w(LOGTAG, "Ignore bad plugin");
                    continue;
                }

                // retrieve information from the plugin's manifest
                PackageInfo pkgInfo;
                try {
                    pkgInfo = pm.getPackageInfo(serviceInfo.packageName,
                                    PackageManager.GET_PERMISSIONS
                                    | PackageManager.GET_SIGNATURES);
                } catch (NameNotFoundException e) {
                    Log.w(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                }
                if (pkgInfo == null) {
                    continue;
                }

                /*
                 * find the location of the plugin's shared library. The default
                 * is to assume the app is either a user installed app or an
                 * updated system app. In both of these cases the library is
                 * stored in the app's data directory.
                 */
                String directory = pkgInfo.applicationInfo.dataDir + "/lib";
                final int appFlags = pkgInfo.applicationInfo.flags;
                final int updatedSystemFlags = ApplicationInfo.FLAG_SYSTEM |
                                               ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
                // preloaded system app with no user updates
                if ((appFlags & updatedSystemFlags) == ApplicationInfo.FLAG_SYSTEM) {
                    directory = PLUGIN_SYSTEM_LIB + pkgInfo.packageName;
                }

                // check if the plugin has the required permissions
                String permissions[] = pkgInfo.requestedPermissions;
                if (permissions == null) {
                    continue;
                }
                boolean permissionOk = false;
                for (String permit : permissions) {
                    if (PLUGIN_PERMISSION.equals(permit)) {
                        permissionOk = true;
                        break;
                    }
                }
                if (!permissionOk) {
                    continue;
                }

                // check to ensure the plugin is properly signed
                Signature signatures[] = pkgInfo.signatures;
                if (signatures == null) {
		    Log.w(LOGTAG, "no signature for " + pkgInfo.packageName);
                    continue;
                }

		// Temp disable signature test, if you want to
		// signature test, please de-comment it following...

		/*
                if (SystemProperties.getBoolean("ro.secure", false)) {
                    boolean signatureMatch = false;
                    for (Signature signature : signatures) {
                        for (int i = 0; i < SIGNATURES.length; i++) {
                            if (SIGNATURES[i].equals(signature)) {
                                signatureMatch = true;
				Log.d(LOGTAG, "plugin:" +  pkgInfo.packageName + " signature test passed.");
                                break;
                            }
                        }
                    }
                    if (!signatureMatch) {
                        continue;
                    }
                }
		*/

                // determine the type of plugin from the manifest
                if (serviceInfo.metaData == null) {
                    Log.e(LOGTAG, "The plugin '" + serviceInfo.name + "' has no type defined");
                    continue;
                }

                String pluginType = serviceInfo.metaData.getString(PLUGIN_TYPE);
                if (!TYPE_NATIVE.equals(pluginType)) {
                    Log.e(LOGTAG, "Unrecognized plugin type: " + pluginType);
                    continue;
                }

                try {
                    Class<?> cls = getPluginClass(serviceInfo.packageName, serviceInfo.name);

                    //TODO implement any requirements of the plugin class here!
                    boolean classFound = true;

                    if (!classFound) {
                        Log.e(LOGTAG, "The plugin's class' " + serviceInfo.name + "' does not extend the appropriate class.");
                        continue;
                    }

                } catch (NameNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                } catch (ClassNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin's class: " + serviceInfo.name);
                    continue;
                }

                // if all checks have passed then make the plugin available
                mPackageInfoCache.add(pkgInfo);
                directories.add(directory);
            }
        }

        return directories.toArray(new String[directories.size()]);
    }

    /* package */
    String getPluginsAPKName(String pluginLib) {

        // basic error checking on input params
        if (pluginLib == null || pluginLib.length() == 0) {
            return null;
        }

        // must be synchronized to ensure the consistency of the cache
        synchronized(mPackageInfoCache) {
            for (PackageInfo pkgInfo : mPackageInfoCache) {
                if (pluginLib.contains(pkgInfo.packageName)) {
                    return pkgInfo.packageName;
                }
            }
        }

        // if no apk was found then return null
        return null;
    }

    String getPluginSharedDataDirectory() {
        return mContext.getDir("plugins", 0).getPath();
    }

    /* package */
    Class<?> getPluginClass(String packageName, String className)
            throws NameNotFoundException, ClassNotFoundException {
        Context pluginContext = mContext.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE |
                Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader pluginCL = pluginContext.getClassLoader();
        return pluginCL.loadClass(className);
    }
}
