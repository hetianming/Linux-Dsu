/*
 * Copyright 2024 The Android Open Source Project
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

package android.crosvm;

import android.crosvm.DisplayConfig;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

/**
 * Service to provide Crosvm with an Android Surface for showing a guest's display.
 *
 * NOTE: This binder is implemented by crosvm itself (see
 * libs/android_display_backend/crosvm_android_display_client.cpp). Only the methods below are
 * implemented there. Input forwarding is NOT done through this interface — that C++ backend has
 * no handle to crosvm's virtio-input devices. Input goes through per-device unix sockets that
 * crosvm reads via `--input ...[path=...]`; see InputSocketHost / InputForwarder / EvdevEncoder.
 */
interface ICrosvmAndroidDisplayService {
    // ── Display surface management ──────────────────────────────────────────
    void setSurface(in Surface surface, boolean forCursor);
    void setCursorStream(in ParcelFileDescriptor stream);
    void removeSurface(boolean forCursor);
    void saveFrameForSurface(boolean forCursor);
    void drawSavedFrameForSurface(boolean forCursor);

    // ── Display configuration (read-only from guest) ────────────────────────
    /**
     * Returns the guest display configuration.
     * @return DisplayConfig with width/height/dpi/refreshRate, or null if not available yet.
     *
     * NOTE: crosvm's current C++ backend does not implement this either; callers must handle
     * the call failing and fall back to a default resolution.
     */
    DisplayConfig getDisplayConfig();
}
