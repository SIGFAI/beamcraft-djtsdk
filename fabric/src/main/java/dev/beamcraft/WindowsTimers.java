package dev.beamcraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WinBase;

/**
 * Windows 11 coarsens timers (to 15.6 ms) and slows down processes whose windows aren't visible.
 * Minecraft's window is hidden while BeamNG shows it, so every short sleep in its frame limiter
 * became 15.6 ms and it rendered at ~15-60 fps no matter how fast the PC is. This opts the process
 * out of that throttling and asks for 1 ms timers.
 */
public final class WindowsTimers {
    private static final int PROCESS_POWER_THROTTLING = 4;                   // PROCESS_INFORMATION_CLASS
    private static final int THROTTLE_EXECUTION_SPEED = 0x1;
    private static final int THROTTLE_IGNORE_TIMER_RESOLUTION = 0x4;

    private WindowsTimers() {}

    public static void apply() {
        if (Platform.get() != Platform.WINDOWS) return;
        try {
            long kernel32 = WinBase.GetModuleHandle("kernel32");
            long getCurrentProcess = WinBase.GetProcAddress(kernel32, "GetCurrentProcess");
            long setProcessInformation = WinBase.GetProcAddress(kernel32, "SetProcessInformation");
            if (getCurrentProcess != 0 && setProcessInformation != 0) {
                long process = JNI.invokeP(getCurrentProcess);
                // PROCESS_POWER_THROTTLING_STATE { Version = 1, ControlMask, StateMask = 0 (= "don't throttle") }
                ByteBuffer state = MemoryUtil.memAlloc(12).order(ByteOrder.nativeOrder());
                state.putInt(0, 1).putInt(4, THROTTLE_EXECUTION_SPEED | THROTTLE_IGNORE_TIMER_RESOLUTION).putInt(8, 0);
                int ok = JNI.invokePPI(process, PROCESS_POWER_THROTTLING, MemoryUtil.memAddress(state), 12, setProcessInformation);
                MemoryUtil.memFree(state);
                BeamCraftClient.LOG.info("BeamCraft: background timer/CPU throttling {}", ok != 0 ? "disabled" : "could not be disabled");
            }
            // BeamNG is the foreground app, so Windows schedules Minecraft's (small) GPU work behind
            // all of BeamNG's and frames finish late. Raise our GPU scheduling and CPU priority.
            long gdi32 = WinBase.LoadLibrary("gdi32");
            long setGpuPriority = gdi32 == 0 ? 0 : WinBase.GetProcAddress(gdi32, "D3DKMTSetProcessSchedulingPriorityClass");
            if (setGpuPriority != 0 && getCurrentProcess != 0) {
                int status = JNI.invokePI(JNI.invokeP(getCurrentProcess), 4 /* HIGH */, setGpuPriority);
                if (status != 0) status = JNI.invokePI(JNI.invokeP(getCurrentProcess), 3 /* ABOVE_NORMAL */, setGpuPriority);
                BeamCraftClient.LOG.info("BeamCraft: GPU scheduling priority {}", status == 0 ? "raised" : "unchanged (status " + status + ")");
            }
            long setPriorityClass = WinBase.GetProcAddress(kernel32, "SetPriorityClass");
            if (setPriorityClass != 0 && getCurrentProcess != 0) {
                JNI.invokePI(JNI.invokeP(getCurrentProcess), 0x80 /* HIGH_PRIORITY_CLASS */, setPriorityClass);
            }

            long winmm = WinBase.LoadLibrary("winmm");
            long timeBeginPeriod = winmm == 0 ? 0 : WinBase.GetProcAddress(winmm, "timeBeginPeriod");
            if (timeBeginPeriod != 0) JNI.invokeI(1, timeBeginPeriod); // 1 ms timer resolution
        } catch (Throwable t) {
            BeamCraftClient.LOG.warn("BeamCraft: could not adjust Windows timers: {}", t.toString());
        }
    }
}
