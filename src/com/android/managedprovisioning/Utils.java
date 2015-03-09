/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning;

import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME;
import static  android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Class containing various auxiliary methods.
 */
public class Utils {
    public static Set<String> getCurrentSystemApps(int userId) {
        IPackageManager ipm = IPackageManager.Stub.asInterface(ServiceManager
                .getService("package"));
        Set<String> apps = new HashSet<String>();
        List<ApplicationInfo> aInfos = null;
        try {
            aInfos = ipm.getInstalledApplications(
                    PackageManager.GET_UNINSTALLED_PACKAGES, userId).getList();
        } catch (RemoteException neverThrown) {
            ProvisionLogger.loge("This should not happen.", neverThrown);
        }
        for (ApplicationInfo aInfo : aInfos) {
            if ((aInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                apps.add(aInfo.packageName);
            }
        }
        return apps;
    }

    public static void disableComponent(ComponentName toDisable, int userId) {
        try {
            IPackageManager ipm = IPackageManager.Stub.asInterface(ServiceManager
                .getService("package"));

            ipm.setComponentEnabledSetting(toDisable,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
                    userId);
        } catch (RemoteException neverThrown) {
            ProvisionLogger.loge("This should not happen.", neverThrown);
        } catch (Exception e) {
            ProvisionLogger.logw("Component not found, not disabling it: "
                + toDisable.toShortString());
        }
    }

    public static ComponentName findDeviceAdminFromIntent(Intent intent, Context c)
            throws IllegalProvisioningArgumentException {
        ComponentName mdmComponentName = intent.getParcelableExtra(
                EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME);
        String mdmPackageName = intent.getStringExtra(
                EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        return findDeviceAdmin(mdmPackageName, mdmComponentName, c);
    }

    /**
     * Exception thrown when the provisioning has failed completely.
     *
     * We're using a custom exception to avoid catching subsequent exceptions that might be
     * significant.
     */
    public static class IllegalProvisioningArgumentException extends Exception {
        public IllegalProvisioningArgumentException(String message) {
            super(message);
        }

        public IllegalProvisioningArgumentException(String message, Throwable t) {
            super(message, t);
        }
    }

    /**
     * Check the validity of the admin component name supplied, or try to infer this componentName
     * from the package.
     *
     * We are supporting lookup by package name for legacy reasons.
     *
     * If mdmComponentName is supplied (not null):
     * mdmPackageName is ignored.
     * Check that the package of mdmComponentName is installed, that mdmComponentName is a
     * receiver in this package, and return it.
     *
     * Otherwise:
     * mdmPackageName must be supplied (not null).
     * Check that this package is installed, try to infer a potential device admin in this package,
     * and return it.
     */
    public static ComponentName findDeviceAdmin(String mdmPackageName,
            ComponentName mdmComponentName, Context c) throws IllegalProvisioningArgumentException {
        if (mdmComponentName != null) {
            mdmPackageName = mdmComponentName.getPackageName();
        }
        if (mdmPackageName == null) {
            throw new IllegalProvisioningArgumentException("Neither the package name nor the"
                    + " component name of the admin are supplied");
        }
        PackageInfo pi;
        try {
            pi = c.getPackageManager().getPackageInfo(mdmPackageName,
                    PackageManager.GET_RECEIVERS);
        } catch (NameNotFoundException e) {
            throw new IllegalProvisioningArgumentException("Mdm "+ mdmPackageName
                    + " is not installed. ", e);
        }
        if (mdmComponentName != null) {
            // If the component was specified in the intent: check that it is in the manifest.
            checkAdminComponent(mdmComponentName, pi);
            return mdmComponentName;
        } else {
            // Otherwise: try to find a potential device admin in the manifest.
            return findDeviceAdminInPackage(mdmPackageName, pi);
        }
    }

    private static void checkAdminComponent(ComponentName mdmComponentName, PackageInfo pi)
            throws IllegalProvisioningArgumentException{
        for (ActivityInfo ai : pi.receivers) {
            if (mdmComponentName.getClassName().equals(ai.name)) {
                return;
            }
        }
        throw new IllegalProvisioningArgumentException("The component " + mdmComponentName
                + " cannot be found");
    }

    private static ComponentName findDeviceAdminInPackage(String mdmPackageName, PackageInfo pi)
            throws IllegalProvisioningArgumentException {
        ComponentName mdmComponentName = null;
        for (ActivityInfo ai : pi.receivers) {
            if (!TextUtils.isEmpty(ai.permission) &&
                    ai.permission.equals(android.Manifest.permission.BIND_DEVICE_ADMIN)) {
                if (mdmComponentName != null) {
                    throw new IllegalProvisioningArgumentException("There are several "
                            + "device admins in " + mdmPackageName + " but no one in specified");
                } else {
                    mdmComponentName = new ComponentName(mdmPackageName, ai.name);
                }
            }
        }
        if (mdmComponentName == null) {
            throw new IllegalProvisioningArgumentException("There are no device admins in"
                    + mdmPackageName);
        }
        return mdmComponentName;
    }
}
