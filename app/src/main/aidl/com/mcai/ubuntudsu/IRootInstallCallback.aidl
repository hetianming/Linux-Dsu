package com.mcai.ubuntudsu;

interface IRootInstallCallback {
    void onStage(String stage, int progress);
    void onFinished(boolean success, String message);
}
