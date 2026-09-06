package com.mcai.ubuntudsu;

import android.os.ParcelFileDescriptor;

interface IPrivilegedService {
    boolean isAvailable();
    boolean startInstallation(String slot);
    int createPartition(String name, long size, boolean readOnly);
    boolean setAshmem(in ParcelFileDescriptor fd, long size);
    boolean submitFromAshmem(long bytes);
    boolean closePartition();
    boolean finishInstallation();
    boolean setEnable(boolean enable, boolean oneShot);
    boolean boot();
    boolean remove();
    boolean abort();
    String listDsuImages();
    String replaceDsuBackingImage(String slot, String imageName, in ParcelFileDescriptor fd, long size, boolean force);
}
