package com.coshare.patientrecord.maintenance.datapurge;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class DataPurgeMaintenanceState {

    private final AtomicBoolean locked = new AtomicBoolean(false);

    public boolean tryLock() {
        return locked.compareAndSet(false, true);
    }

    public void unlock() {
        locked.set(false);
    }

    public boolean isLocked() {
        return locked.get();
    }
}
