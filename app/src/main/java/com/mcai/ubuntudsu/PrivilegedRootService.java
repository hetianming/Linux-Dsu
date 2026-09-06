package com.mcai.ubuntudsu;

import android.os.IBinder;
import android.content.Intent;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.topjohnwu.superuser.ipc.RootService;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Root-side bridge for the hidden Dynamic System Binder APIs. */
public final class PrivilegedRootService extends RootService {
    private static final String TAG = "UbuntuDsuRoot";
    private final IPrivilegedService.Stub bridge = new IPrivilegedService.Stub() {
        @Override public boolean isAvailable() { return dynamicSystem() != null; }
        @Override public boolean startInstallation(String slot) { return transactBoolean(1, p -> p.writeString(slot)); }
        @Override public int createPartition(String name, long size, boolean readOnly) {
            return transactInt(2, p -> { p.writeString(name); p.writeLong(size); p.writeInt(readOnly ? 1 : 0); });
        }
        @Override public boolean setAshmem(ParcelFileDescriptor fd, long size) {
            return transactBoolean(12, p -> { p.writeTypedObject(fd, 0); p.writeLong(size); });
        }
        @Override public boolean submitFromAshmem(long bytes) { return transactBoolean(13, p -> p.writeLong(bytes)); }
        @Override public boolean closePartition() { return transactBoolean(3, null); }
        @Override public boolean finishInstallation() { return transactBoolean(4, null); }
        @Override public boolean setEnable(boolean enable, boolean oneShot) {
            return transactBoolean(11, p -> { p.writeInt(enable ? 1 : 0); p.writeInt(oneShot ? 1 : 0); });
        }
        @Override public boolean boot() {
            try {
                return Runtime.getRuntime().exec(new String[]{"/system/bin/setprop", "sys.powerctl", "reboot,dsu"}).waitFor() == 0;
            } catch (Exception e) { Log.e(TAG, "DSU boot failed", e); return false; }
        }
        @Override public boolean remove() { return transactBoolean(10, null); }
        @Override public boolean abort() { return transactBoolean(6, null); }
        @Override public String listDsuImages() {
            try {
                Object gsi = gsiInterface();
                Object imageService = invokeHidden("android.gsi.IGsiService", gsi, "openImageService", "dsu");
                Object images = invokeHidden("android.gsi.IImageService", imageService, "getAllBackingImages");
                if (images instanceof List) return images.toString();
            } catch (Exception e) { return "ERROR|" + e.getMessage(); }
            return "EMPTY|暂无已安装镜像";
        }
        @Override public String replaceDsuBackingImage(String slot, String imageName, ParcelFileDescriptor fd, long size, boolean force) {
            if (fd == null || size <= 0) return "镜像文件为空";
            try {
                Object service = imageService(slot);
                boolean exists = (Boolean) invokeHidden("android.gsi.IImageService", service, "backingImageExists", imageName);
                boolean mapped = (Boolean) invokeHidden("android.gsi.IImageService", service, "isImageMapped", imageName);
                if (exists && !force) return "镜像已存在，请确认替换";
                if (mapped) invokeHidden("android.gsi.IImageService", service, "unmapImageDevice", imageName);
                if (exists) invokeHidden("android.gsi.IImageService", service, "deleteBackingImage", imageName);
                invokeHidden("android.gsi.IImageService", service, "createBackingImage", imageName, size, 1, null);
                String path = mapImage(service, imageName);
                copyTo(new File(path), fd, size);
                invokeHidden("android.gsi.IImageService", service, "unmapImageDevice", imageName);
                return "";
            } catch (Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
            finally { try { fd.close(); } catch (Exception ignored) {} }
        }
    };

    @Override public IBinder onBind(Intent intent) { return bridge; }

    private interface Writer { void write(Parcel parcel); }
    private IBinder dynamicSystem() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            return (IBinder) manager.getMethod("getService", String.class).invoke(null, "dynamic_system");
        } catch (Exception e) { return null; }
    }
    private boolean transactBoolean(int code, Writer writer) {
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService");
            if (writer != null) writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return false;
            reply.readException(); return reply.readInt() != 0;
        } catch (Exception e) { Log.e(TAG, "dynamic_system transaction " + code, e); return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    private int transactInt(int code, Writer writer) {
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService"); writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return -1;
            reply.readException(); return reply.readInt();
        } catch (Exception e) { Log.e(TAG, "dynamic_system transaction " + code, e); return -1; }
        finally { data.recycle(); reply.recycle(); }
    }
    private Object gsiInterface() throws Exception {
        Class<?> stub = Class.forName("android.gsi.IGsiService$Stub");
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "gsiservice");
        if (binder == null) throw new IOException("gsiservice unavailable");
        return HiddenApiBypass.invoke(stub, null, "asInterface", binder);
    }
    private Object imageService(String slot) throws Exception { return invokeHidden("android.gsi.IGsiService", gsiInterface(), "openImageService", slot); }
    private Object invokeHidden(String type, Object instance, String method, Object... args) throws Exception {
        return HiddenApiBypass.invoke(Class.forName(type), instance, method, args);
    }
    private String mapImage(Object service, String name) throws Exception {
        Class<?> type = Class.forName("android.gsi.MappedImage"); Object mapped = type.newInstance();
        invokeHidden("android.gsi.IImageService", service, "mapImageDevice", name, 10000, mapped);
        java.lang.reflect.Field path = type.getDeclaredField("path"); path.setAccessible(true); return (String) path.get(mapped);
    }
    private void copyTo(File target, ParcelFileDescriptor fd, long expected) throws IOException {
        long copied = 0;
        try (ParcelFileDescriptor dup = ParcelFileDescriptor.dup(fd.getFileDescriptor());
             ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(dup);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[4 * 1024 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); copied += count; }
            output.getFD().sync();
        }
        if (copied != expected) throw new IOException("镜像写入长度不一致");
    }
}
