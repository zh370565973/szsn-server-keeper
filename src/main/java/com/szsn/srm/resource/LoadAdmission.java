package com.szsn.srm.resource;

/**
 * 槽位占用与下一次启动的准入条件。纯判断，不启动线程，也不分配内存。
 */
final class LoadAdmission {
    private LoadAdmission() {
    }

    static boolean quiescent(boolean workersExited, long heldBytes) {
        return workersExited && heldBytes == 0;
    }

    static String rejection(ExecutionStatus occupant, boolean workersExited, long heldBytes) {
        if (occupant != null && occupant.occupiesSlot()) {
            return "SLOT_OCCUPIED";
        }
        if (!workersExited) {
            return "WORKERS_ACTIVE";
        }
        if (heldBytes != 0) {
            return "LOAD_HELD";
        }
        return null;
    }
}
