package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;

import android.content.pm.ServiceInfo;
import org.junit.Test;

public class ForegroundServiceTypeSelectionTest {

    @Test
    public void idleXmppUsesSpecialUseEvenWithMicAndCameraGranted() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                XmppConnectionService.selectForegroundServiceType(false, false, true, true, false));
    }

    @Test
    public void idleXmppUsesSystemExemptedIfBatteryOptimizationExempt() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                XmppConnectionService.selectForegroundServiceType(false, false, true, true, true));
    }

    @Test
    public void audioCallUsesMicrophoneButNotUnusedCamera() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                XmppConnectionService.selectForegroundServiceType(true, false, true, true, false));
    }

    @Test
    public void videoCallUsesOnlyRequiredAndGrantedTypes() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                XmppConnectionService.selectForegroundServiceType(true, true, true, true, false));
    }

    @Test
    public void missingCallPermissionsNeverFabricateMicrophoneOrCameraTypes() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                XmppConnectionService.selectForegroundServiceType(true, true, false, false, false));
    }

    @Test
    public void cameraOnlyPermissionDoesNotEnableMissingMicrophone() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                XmppConnectionService.selectForegroundServiceType(true, true, false, true, false));
    }
}
