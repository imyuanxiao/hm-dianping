package com.hmdp.utils;

public interface ILock {

    Boolean tryLock(Long time);

    void unLock();

}
