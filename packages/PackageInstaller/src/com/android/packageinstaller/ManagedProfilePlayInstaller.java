package com.android.packageinstaller;

import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ext.PackageId;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Installs sandboxed Google Play into a newly created work profile when its admin (DPC) app
 * depends on Google Play services.
 *
 * <p>Sandboxed Google Play is a set of regular apps and isn't installed into new users and
 * profiles automatically. DPC apps that depend on Google Play services (e.g. Android Device Policy,
 * Microsoft Intune Company Portal) fail to set up the work profile if it doesn't have them.
 *
 * <p>This runs as part of the PackageInstaller app inside of the new profile, at
 * LOCKED_BOOT_COMPLETED of its first start, which is the earliest point at which app code is able
 * to run in the profile. This is intentionally not done by DevicePolicyManagerService: the
 * PackageInstaller app is far less privileged than system_server, and this code doesn't need any
 * permissions that the PackageInstaller app doesn't already hold:
 * <ul>
 *     <li>INSTALL_PACKAGES, for installing the existing Play packages into the profile</li>
 *     <li>MANAGE_APP_OPS_MODES, for allowing Play Store to request installs</li>
 *     <li>MANAGE_USERS and INTERACT_ACROSS_USERS_FULL, for querying other users</li>
 * </ul>
 *
 * <p>Play packages are only ever copied from other users of the device, i.e. they must already be
 * installed by the user in at least one of them, and their signatures must be valid. The main user
 * is checked first, followed by the remaining users. Packages are shared between users, so the
 * choice of user doesn't affect what gets installed.
 */
public class ManagedProfilePlayInstaller extends BroadcastReceiver {
    private static final String TAG = "ManagedProfilePlayInstaller";

    private static final String GMS_VERSION_METADATA = "com.google.android.gms.version";

    // GmsCore is installed first, Play Store depends on it.
    // GSF is intentionally omitted, it's not needed by current versions of GmsCore.
    private static final String[] PLAY_PACKAGES = {
            PackageId.GMS_CORE_NAME,
            PackageId.PLAY_STORE_NAME,
    };

    private static final int MATCH_ANY_DIRECT_BOOT_STATE =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        UserManager um = context.getSystemService(UserManager.class);
        if (!um.isManagedProfile()) {
            return;
        }

        // A user becomes initialized after its first unlock is completed. Restricting this to the
        // first start of the profile ensures that Play packages are never reinstalled after the
        // user uninstalls them, and that this doesn't apply to pre-existing profiles.
        UserInfo profileInfo = um.getUserInfo(context.getUserId());
        if (profileInfo == null || profileInfo.isInitialized()) {
            return;
        }

        try {
            maybeInstallPlay(context, um);
        } catch (RuntimeException e) {
            // Failure to install Play shouldn't break profile setup for DPCs that don't need it
            Log.e(TAG, "unable to install Play into managed profile", e);
        }
    }

    private static void maybeInstallPlay(Context context, UserManager um) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName profileOwner = dpm.getProfileOwner();
        if (profileOwner == null) {
            Log.d(TAG, "managed profile has no profile owner");
            return;
        }

        PackageManager pm = context.getPackageManager();
        if (!requiresPlay(pm, profileOwner.getPackageName())) {
            return;
        }
        Log.i(TAG, profileOwner.getPackageName() + " depends on Google Play services");

        List<UserHandle> sourceUsers = getSourceUsers(um);
        for (String pkgName : PLAY_PACKAGES) {
            UserHandle sourceUser = findUserWithGenuinePlayPackage(context, sourceUsers, pkgName);
            if (sourceUser == null) {
                Log.w(TAG, pkgName + " is not installed in any other user, or is not genuine");
                return;
            }
            Log.d(TAG, "found " + pkgName + " in user " + sourceUser.getIdentifier());
        }

        for (String pkgName : PLAY_PACKAGES) {
            if (isInstalled(pm, pkgName)) {
                continue;
            }
            Log.i(TAG, "installing " + pkgName);
            final int res;
            try {
                // Unlike PackageManager#installExistingPackage(), this doesn't allowlist
                // restricted permissions of the package
                res = AppGlobals.getPackageManager().installExistingPackageAsUser(pkgName,
                        context.getUserId(), /* installFlags= */ 0,
                        PackageManager.INSTALL_REASON_POLICY,
                        /* whiteListedPermissions= */ null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (res != PackageManager.INSTALL_SUCCEEDED) {
                Log.e(TAG, "unable to install " + pkgName + ", result: " + res);
                return;
            }
        }

        // Unknown sources settings are commonly locked by the work profile admin, which means that
        // the user isn't able to allow Play Store to request installs after setup is completed
        allowPlayStoreToRequestInstalls(context, pm);
    }

    /**
     * Returns all users other than this profile, with the main user first.
     */
    private static List<UserHandle> getSourceUsers(UserManager um) {
        UserHandle self = Process.myUserHandle();
        UserHandle mainUser = um.getMainUser();
        var res = new ArrayList<UserHandle>();
        if (mainUser != null && !mainUser.equals(self)) {
            res.add(mainUser);
        }
        for (UserHandle user : um.getUserHandles(/* excludeDying= */ true)) {
            if (!user.equals(self) && !user.equals(mainUser)) {
                res.add(user);
            }
        }
        return res;
    }

    @Nullable
    private static UserHandle findUserWithGenuinePlayPackage(Context context,
            List<UserHandle> users, String pkgName) {
        for (UserHandle user : users) {
            PackageManager userPm = context.createContextAsUser(user, 0).getPackageManager();
            if (isGenuinePlayPackage(userPm, pkgName)) {
                return user;
            }
        }
        return null;
    }

    private static boolean requiresPlay(PackageManager pm, String pkgName) {
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkgName,
                    PackageManager.GET_META_DATA | MATCH_ANY_DIRECT_BOOT_STATE);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        Bundle metaData = ai.metaData;
        return metaData != null && metaData.containsKey(GMS_VERSION_METADATA);
    }

    private static boolean isGenuinePlayPackage(PackageManager pm, String pkgName) {
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkgName, MATCH_ANY_DIRECT_BOOT_STATE);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        int expectedId = PackageId.PLAY_STORE_NAME.equals(pkgName)
                ? PackageId.PLAY_STORE : PackageId.GMS_CORE;
        // PackageId is assigned by the OS only after verifying the package signature
        return ai.ext().getPackageId() == expectedId;
    }

    private static boolean isInstalled(PackageManager pm, String pkgName) {
        try {
            pm.getApplicationInfo(pkgName, MATCH_ANY_DIRECT_BOOT_STATE);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void allowPlayStoreToRequestInstalls(Context context, PackageManager pm) {
        ApplicationInfo storeInfo;
        try {
            storeInfo = pm.getApplicationInfo(PackageId.PLAY_STORE_NAME,
                    MATCH_ANY_DIRECT_BOOT_STATE);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Play Store is missing after installation", e);
            return;
        }
        if (storeInfo.ext().getPackageId() != PackageId.PLAY_STORE) {
            Log.e(TAG, "Play Store in managed profile is not genuine");
            return;
        }
        Log.i(TAG, "allowing Play Store to request installs");
        context.getSystemService(AppOpsManager.class).setMode(
                AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES, storeInfo.uid,
                PackageId.PLAY_STORE_NAME, AppOpsManager.MODE_ALLOWED);
    }
}
