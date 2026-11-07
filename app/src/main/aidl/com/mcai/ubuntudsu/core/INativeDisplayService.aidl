package com.mcai.ubuntudsu.core;

/**
 * 主程序自建的 root 侧桥（v2.0 内置原生显示）。
 *
 * 两件事：
 *   1) 以 uid=0 的 SELinux 域去 ServiceManager 查 crosvm 注册的
 *      "crosvm_display" binder 并返回给 APP 进程（显示）。
 *   2) 托管 crosvm 输入用的 4 个 unix socket（v2.0.10 起），APP 通过
 *      writeInput() 把编好的 evdev 字节交给本 root 进程直写 socket。
 *
 * v2.0.10 架构变更：彻底去掉 native touchd + APP 直连 127.0.0.1 TCP 这层桥。
 * untrusted_app 进程直连本地 TCP 在真机上会被 SELinux / 网络策略拦截，正是
 * 连续多版「画面正常、但触摸/键盘/鼠标全都不能操作」的根因。输入 socket 改由
 * 本 root 服务进程(uid=0, permissive 域)持有，APP 只走 binder（已允许），
 * 与已验证可用的 Crosvm-Android 参考实现架构一致。
 */
interface INativeDisplayService {
    /** 阻塞等待并显示服务注册；找不到返回 null。 */
    IBinder waitForDisplayBinder(String name);
    /** 供界面显示诊断信息（uid / 服务是否存在）。 */
    String diagnostics(String name);

    /** 在 crosvm 启动前 bind+listen 好 4 路输入 unix socket（幂等、常驻 accept）。 */
    void ensureInputListening();
    /** 各路 crosvm 事件源【是否 alive】（索引 0=触摸 1=键盘 2=鼠标 3=开关），实时快照。 */
    boolean[] getInputChannelsReady();
    /** root 进程把 evdev 字节写进对应 socket；返回 true 表示确已写入。 */
    boolean writeInput(int channel, in byte[] data);
    /** 输入链路真实诊断：每路 alive 连接数/累计连接/挂断次数/回读字节/收发计数/最后错误。 */
    String inputDiagnostics();
}
