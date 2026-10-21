// Only the already-authorized framework usbfs fd is used. No root, reset or configuration ioctl.
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

static int valid(jint fd, jint id) { return fd >= 0 && id >= 0 && id <= 255; }
static int result(int rc) { return rc < 0 ? errno : 0; }

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_claim(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    unsigned int interface_id = (unsigned int) id;
    return result(ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface_id));
}

JNIEXPORT jstring JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_driver(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) self;
    struct usbdevfs_getdriver query;
    memset(&query, 0, sizeof(query));
    query.interface = (unsigned int) id;
    int err = valid(fd, id) ? result(ioctl(fd, USBDEVFS_GETDRIVER, &query)) : EINVAL;
    query.driver[sizeof(query.driver) - 1] = '\0';
    char value[USBDEVFS_MAXDRIVERNAME + 32];
    snprintf(value, sizeof(value), "%d:%s", err, err == 0 ? query.driver : "");
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_disconnectClaim(
        JNIEnv *env, jobject self, jint fd, jint id, jstring driver) {
    (void) self;
    if (!valid(fd, id) || driver == NULL) return EINVAL;
    const char *name = (*env)->GetStringUTFChars(env, driver, NULL);
    if (name == NULL) return ENOMEM;
    // Defense in depth: never detach another userspace owner, even if Kotlin is called incorrectly.
    if (strcmp(name, "cdc_ncm") != 0 && strcmp(name, "cdc_ether") != 0) {
        (*env)->ReleaseStringUTFChars(env, driver, name);
        return EINVAL;
    }
    struct usbdevfs_disconnect_claim request;
    memset(&request, 0, sizeof(request));
    request.interface = (unsigned int) id;
    request.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    memcpy(request.driver, name, strlen(name) + 1);
    (*env)->ReleaseStringUTFChars(env, driver, name);
    // One ioctl, one attempt. The driver-name check and detach/claim run under the device lock.
    return result(ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &request));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_reconnectIfUnbound(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    struct usbdevfs_getdriver query;
    memset(&query, 0, sizeof(query));
    query.interface = (unsigned int) id;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &query) == 0) return 0; // Already bound; leave it alone.
    if (errno != ENODATA) return errno;
    struct usbdevfs_ioctl request;
    memset(&request, 0, sizeof(request));
    request.ifno = id;
    request.ioctl_code = USBDEVFS_CONNECT;
    return result(ioctl(fd, USBDEVFS_IOCTL, &request));
}

/*
 * Direct usbfs mode (issue #266): transfer primitives on a raw /dev/bus/usb fd that
 * DirectUsbAccess opened without the framework. Same contract as the rest of this file:
 * no exceptions cross JNI; transfers return the byte count (>= 0) or -errno.
 * The buffer is a direct ByteBuffer address supplied by Kotlin, so no array pinning or
 * copies happen here beyond the kernel's own.
 */

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_controlTransfer(
        JNIEnv *env, jobject self, jint fd, jint requestType, jint request, jint value,
        jint index, jobject buffer, jint length, jint timeoutMillis) {
    (void) self;
    if (fd < 0 || buffer == NULL || length < 0 || timeoutMillis < 0) return -EINVAL;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    if (data == NULL) return -EINVAL;
    struct usbdevfs_ctrltransfer ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.bRequestType = (__u8) requestType;
    ctrl.bRequest = (__u8) request;
    ctrl.wValue = (__u16) value;
    ctrl.wIndex = (__u16) index;
    ctrl.wLength = (__u16) length;
    ctrl.timeout = (unsigned int) timeoutMillis;
    ctrl.data = data;
    int rc = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    return rc < 0 ? -errno : rc;
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_bulkTransfer(
        JNIEnv *env, jobject self, jint fd, jint endpoint, jobject buffer, jint length,
        jint timeoutMillis) {
    (void) self;
    if (fd < 0 || buffer == NULL || length < 0 || timeoutMillis < 0) return -EINVAL;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    if (data == NULL) return -EINVAL;
    struct usbdevfs_bulktransfer bulk;
    memset(&bulk, 0, sizeof(bulk));
    bulk.ep = (unsigned int) endpoint;
    bulk.len = (unsigned int) length;
    bulk.timeout = (unsigned int) timeoutMillis;
    bulk.data = data;
    int rc = ioctl(fd, USBDEVFS_BULK, &bulk);
    return rc < 0 ? -errno : rc;
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_nativeSetConfiguration(
        JNIEnv *env, jobject self, jint fd, jint configuration) {
    (void) env; (void) self;
    if (fd < 0 || configuration < 0 || configuration > 255) return EINVAL;
    unsigned int value = (unsigned int) configuration;
    return result(ioctl(fd, USBDEVFS_SETCONFIGURATION, &value));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_nativeReleaseInterface(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    unsigned int interface_id = (unsigned int) id;
    return result(ioctl(fd, USBDEVFS_RELEASEINTERFACE, &interface_id));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_nativeClaimInterface(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    unsigned int interface_id = (unsigned int) id;
    return result(ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface_id));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_nativeSetInterface(
        JNIEnv *env, jobject self, jint fd, jint id, jint alt) {
    (void) env; (void) self;
    if (!valid(fd, id) || alt < 0 || alt > 255) return EINVAL;
    struct usbdevfs_setinterface request;
    memset(&request, 0, sizeof(request));
    request.interface = (unsigned int) id;
    request.altsetting = (unsigned int) alt;
    return result(ioctl(fd, USBDEVFS_SETINTERFACE, &request));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_UsbfsIoNative_nativeResetDevice(
        JNIEnv *env, jobject self, jint fd) {
    (void) env; (void) self;
    if (fd < 0) return EINVAL;
    return result(ioctl(fd, USBDEVFS_RESET, NULL));
}
