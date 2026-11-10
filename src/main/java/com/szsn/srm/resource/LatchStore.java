package com.szsn.srm.resource;

import java.util.Optional;

/**
 * 急停锁存的持久化。读取失败由调用方按已锁存处理；写入失败不得撤销已经完成的停止。
 */
public interface LatchStore {
    Optional<StoredLatch> load();

    void save(StoredLatch latch);
}
