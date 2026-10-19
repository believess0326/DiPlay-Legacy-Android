package com.shilapi.xcertplay.usbbt;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * Standalone entry-point for granting and persisting USB permissions via app_process (under root or local ADB).
 *
 * Invoked as:
 * /system/bin/app_process -Djava.class.path=<apkPath> /system/bin com.shilapi.xcertplay.usbbt.UsbDefaultGrantMain <pkg> <uid> <vid> <pid> [userId]
 */
public final class UsbDefaultGrantMain {
    private UsbDefaultGrantMain() {
    }

    private static void fail(String str) {
        System.out.println("FA_USB_LIVE=fail " + str);
    }

    public static void main(String[] strArr) {
        try {
            if (strArr.length < 4) {
                fail("usage: UsbDefaultGrantMain <package> <uid> <vid> <pid> [userId]");
                return;
            }
            String str = strArr[0];
            int i = Integer.parseInt(strArr[1]);
            int i2 = Integer.parseInt(strArr[2]);
            int i3 = Integer.parseInt(strArr[3]);
            int i4 = strArr.length > 4 ? Integer.parseInt(strArr[4]) : 0;
            boolean z = true;
            for (String str2 : strArr) {
                if ("--no-default".equals(str2)) {
                    z = false;
                }
            }
            UsbDevice usbDevice = null;
            IBinder iBinder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "usb");
            if (iBinder == null) {
                fail("no usb service");
                return;
            }
            Object objInvoke = Class.forName("android.hardware.usb.IUsbManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, iBinder);
            Bundle bundle = new Bundle();
            objInvoke.getClass().getMethod("getDeviceList", Bundle.class).invoke(objInvoke, bundle);
            Iterator<String> it = bundle.keySet().iterator();
            while (it.hasNext()) {
                Parcelable parcelable = bundle.getParcelable(it.next());
                if (parcelable instanceof UsbDevice) {
                    UsbDevice usbDevice2 = (UsbDevice) parcelable;
                    if (usbDevice2.getVendorId() == i2 && usbDevice2.getProductId() == i3) {
                        usbDevice = usbDevice2;
                        break;
                    }
                }
            }
            if (usbDevice == null) {
                fail("device not attached");
                return;
            }
            Method method = method(objInvoke, "grantDevicePermission", 2);
            if (method == null) {
                fail("no grantDevicePermission");
                return;
            }
            method.invoke(objInvoke, usbDevice, Integer.valueOf(i));
            if (!z) {
                System.out.println("FA_USB_LIVE=ok");
                return;
            }
            Method method2 = method(objInvoke, "setDevicePackage", 3);
            if (method2 != null) {
                method2.invoke(objInvoke, usbDevice, str, Integer.valueOf(i4));
            } else {
                Method method3 = method(objInvoke, "setDevicePackage", 2);
                if (method3 == null) {
                    fail("no setDevicePackage");
                    return;
                }
                method3.invoke(objInvoke, usbDevice, str);
            }
            System.out.println("FA_USB_LIVE=ok");
        } catch (Throwable th) {
            if ((th instanceof InvocationTargetException) && th.getCause() != null) {
                th = th.getCause();
            }
            fail(th.getClass().getSimpleName() + ": " + th.getMessage());
        }
    }

    private static Method method(Object obj, String str, int i) {
        for (Method method : obj.getClass().getMethods()) {
            if (method.getName().equals(str) && method.getParameterTypes().length == i) {
                return method;
            }
        }
        return null;
    }
}
