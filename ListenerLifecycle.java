package com.axuan.vivoislandplus;

/** Connection can be delivered during the registration Binder call. */
final class ListenerLifecycle {
    private boolean registered,connected,failed;
    synchronized boolean registered(){if(failed)return false;registered=true;return connected;}
    synchronized boolean connected(){if(failed)return false;connected=true;return registered;}
    synchronized void failed(){failed=true;}
    synchronized boolean ready(){return registered&&connected&&!failed;}
}
