package com.android.packageinstaller;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.ext.PackageId;
import android.os.UserManager;

/**
 * Installers that are trusted inside of a managed (work) profile.
 *
 * <p>Work profiles have the DISALLOW_INSTALL_UNKNOWN_SOURCES restriction by default, and admins
 * commonly prevent the user from allowing apps to install other apps. On stock OSes, apps are
 * distributed to work profiles by a preinstalled privileged app store that holds INSTALL_PACKAGES
 * (i.e. a trusted source), or by the admin (DPC) app itself. Neither is a trusted source here,
 * which means that every install they attempt is blocked, including installs of apps that are
 * mandated by the admin.
 *
 * <p>Android has no API for the admin to designate an app store, so the following installers are
 * trusted inside of a managed profile:
 * <ul>
 *     <li>the profile owner (admin app) of the profile, i.e. the party that is responsible for the
 *     unknown sources restriction in the first place</li>
 *     <li>genuine (signature-verified) Play Store, which is the store that is used for
 *     distributing apps to Android Enterprise work profiles</li>
 * </ul>
 *
 * <p>Trust only applies to installs that are done via PackageInstaller sessions. It exempts them
 * from unknown sources checks (both the device policy restrictions and the per-app "install unknown
 * apps" setting). DISALLOW_INSTALL_APPS is still enforced, so the admin is still able to block all
 * installs, and user confirmation is still required.
 */
public final class ManagedProfileTrustedInstallers {

    private ManagedProfileTrustedInstallers() {}

    /**
     * @param sourceInfo ApplicationInfo of the package that launched the install confirmation
     * @param originatingUid uid that is considered to be the origin of this install
     * @param isIntentInstall whether this install was started via ACTION_VIEW or
     *                        ACTION_INSTALL_PACKAGE rather than via a PackageInstaller session
     */
    public static boolean isTrustedSessionInstaller(Context context,
            @Nullable ApplicationInfo sourceInfo, int originatingUid, boolean isIntentInstall) {
        // Intent-based installs from any app are always subject to unknown sources checks
        if (isIntentInstall || sourceInfo == null) {
            return false;
        }
        // Make sure that the install isn't proxied on behalf of another app
        if (sourceInfo.uid != originatingUid) {
            return false;
        }
        if (!context.getSystemService(UserManager.class).isManagedProfile()) {
            return false;
        }
        // PackageId is assigned by the OS only after verifying the package signature
        if (sourceInfo.ext().getPackageId() == PackageId.PLAY_STORE) {
            return true;
        }
        ComponentName profileOwner =
                context.getSystemService(DevicePolicyManager.class).getProfileOwner();
        return profileOwner != null && profileOwner.getPackageName().equals(sourceInfo.packageName);
    }
}
