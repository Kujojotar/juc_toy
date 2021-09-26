package aqswithlock;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;

/**
 * @author james
 * 简单的独占锁版本，还未涉及共享锁以及条件变量
 * @date 2021-09-26 更新了简单的conditionObject类
 */
public class Synchronizer {
    private static final Unsafe unsafe=getUnsafe();

    protected volatile transient int state=0;

    static final class Node{

        static final Node EXCLUSIVE = null;

        static final int CANCELLED = 1;

        static final int SIGNAL=-1;

        static final int CONDITION = -2;

        Node prev;

        Node next;

        Thread thread;

        int waitStatus=0;

        Node nextWaiter;

        Node(){};

        Node(Thread t, Node mode){
            this.thread = t;
            this.nextWaiter = mode;
        }

        Node(Thread t, int waitStatus){
            this.waitStatus = waitStatus;
            this.thread = t;
        }

        Node getPredecessor()throws NullPointerException{
            Node p = prev;
            if(p==null){
                throw new NullPointerException();
            }else{
                return p;
            }
        }
    }

    protected void setState(int newState){
        state = newState;
    }

    protected volatile transient Node head;

    protected volatile transient Node tail;

    protected volatile transient Thread exclusiveThread;

    public Thread getExclusiveThread() {
        return exclusiveThread;
    }

    public void setExclusiveThread(Thread exclusiveThread) {
        this.exclusiveThread = exclusiveThread;
    }

    protected Node enq(Node node){
        for(;;){
            Node t = tail;
            if(t==null){
                if(compareAndSetHead(null,new Node())){
                    tail = head;
                }
            }else{
                node.prev = t;
                if(compareAndSetTail(t, node)){
                    t.next = node;
                    return t;
                }
            }
        }
    }

    protected Node addWaiter(Node mode){
        Node node = new Node(Thread.currentThread(), mode);
        Node pred = tail;
        if(pred!=null){
            node.prev = pred;
            if(compareAndSetTail(pred, node)){
                pred.next = node;
                return node;
            }
        }
        enq(node);
        return node;
    }

    protected void setHead(Node node){
        head = node;
        node.thread=null;
        node.prev=null;
    }

    protected void unParkSuccessor(Node node){
        int ws = node.waitStatus;
        if(ws<0){
            compareAndSetWaitStatus(node, ws, 0);
        }

        Node s = node.next;
        if(s == null|| s.waitStatus>0){
            s=null;
            for(Node t = tail; t!=null && t!=node ; t=t.prev){
                if(t.waitStatus<=0){
                    s = t;
                }
            }
        }
        if(s!=null){
            LockSupport.unpark(s.thread);
        }
    }

    protected boolean acquireQueued(Node node, int arg){
        boolean failed = true;
        try{
            boolean interrupted = false;
            for(;;){
                Node pred = node.prev;
                if(head==pred&&tryAcquire(arg)){
                    setHead(node);
                    pred.next = null;
                    failed = false;
                    return interrupted;
                }
                if(shouldParkAfterFailedAcquire(pred, node)&&parkAndCheckInterrupt()){
                    interrupted = true;
                }
            }
        }catch (Error e){
            addWaiter(node);
            return false;
        }
    }

    protected boolean release(int arg){
        if(tryRelease(arg)){
            Node h = head;
            if(h!=null){
                unParkSuccessor(h);
            }
            return true;
        }
        return false;
    }

    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    protected void acquire(int arg){
        if(!tryAcquire(arg)&&acquireQueued(addWaiter(Node.EXCLUSIVE), arg)){
            selfInterrupt();
        }
    }

    protected boolean shouldParkAfterFailedAcquire(Node pred, Node node){
        int ws = pred.waitStatus;
        if(ws == Node.SIGNAL){
            return true;
        }
        if(ws>0){
            do{
                node.prev = pred = pred.prev;
            }while(pred.waitStatus > 0);
            pred.next = node;
        }else{
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    protected boolean tryAcquire(int arg){
        throw new UnsupportedOperationException();
    }

    static void selfInterrupt(){
        Thread.currentThread().interrupt();
    }

    protected boolean parkAndCheckInterrupt(){
        LockSupport.park(this);
        return Thread.currentThread().isInterrupted();
    }

    public int getState() {
        return state;
    }

    final boolean isOnSyncQueue(Node node){
        if(node.waitStatus == Node.CONDITION || node.prev == null){
            return false;
        }
        if(node.next != null){
            return true;
        }
        return findNodeFromTail(node);
    }

    private boolean findNodeFromTail(Node node){
        Node t = tail;
        for(;;){
            if(t==node){
                return true;
            }
            if(t==null){
                return false;
            }
            t = t.prev;
        }
    }

    protected boolean isHeldExclusively(){
        return Thread.currentThread() == exclusiveThread;
    }

    private int fullyRelease(Node node){
        boolean fail = true;
            int state = getState();
            if(release(state)){
                fail = false;
                return state;
            }else{
                throw new IllegalMonitorStateException();
            }
    }


    public class ConditionObject{
        private transient Node firstWaiter;

        private transient Node lastWaiter;

        public ConditionObject(){};

        private Node addConditionWaiter(){
            Node t = lastWaiter;
            if(t!=null&&t.waitStatus!=Node.CONDITION){
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if(t!=null){
                t.nextWaiter = node;
            }else{
                firstWaiter = node;
            }
            lastWaiter = node;
            return node;
        }

        private void unlinkCancelledWaiters(){
            Node t = firstWaiter;
            Node trail = null;
            while(t!=null){
                Node next = t.nextWaiter;
                if(t.waitStatus != Node.CONDITION){
                    t.nextWaiter = null;
                    if(trail == null){
                        firstWaiter = next;
                    }else{
                        trail.nextWaiter = next;
                    }
                    if(next == null){
                        lastWaiter = trail;
                    }
                }else{
                    trail = t;
                }
                t = t.next;
            }
        }

        public final void await() throws InterruptedException{
            if(Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int saveState = fullyRelease(node);
            while(!isOnSyncQueue(node)){
                LockSupport.park(this);
            }
            acquireQueued(node, saveState);
        }

        public final void signal(){
            if(!isHeldExclusively()){
                throw new IllegalMonitorStateException();
            }
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        private void doSignal(Node first) {
            do {
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        final boolean transferForSignal(Node node) {
            /*
             * If cannot change waitStatus, the node has been cancelled.
             */
            if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
                return false;

            /*
             * Splice onto queue and try to set waitStatus of predecessor to
             * indicate that thread is (probably) waiting. If cancelled or
             * attempt to set waitStatus fails, wake up to resync (in which
             * case the waitStatus can be transiently and harmlessly wrong).
             */
            Node p = enq(node);
            int ws = p.waitStatus;
            if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
                LockSupport.unpark(node.thread);
            return true;
        }

    }


    protected boolean compareAndSetState(int expect, int update){
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    protected boolean compareAndSetHead(Node expect, Node update){
        return unsafe.compareAndSwapObject(this, headOffset, expect, update);
    }

    protected boolean compareAndSetTail(Node expect,Node update){
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    protected boolean compareAndSetNext(Node node, Node expect, Node update){
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }

    protected boolean compareAndSetWaitStatus(Node node, int expect, int update){
        return unsafe.compareAndSwapInt(node, waitStatusOffset, expect, update);
    }

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return unsafe;
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    public final long stateOffset;

    public final long headOffset;

    public final long tailOffset;

    public final long nextOffset;

    public final long waitStatusOffset;

    {
        try {
            stateOffset = unsafe.objectFieldOffset(Synchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset(Synchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset(Synchronizer.class.getDeclaredField("tail"));
            nextOffset = unsafe.objectFieldOffset(Synchronizer.Node.class.getDeclaredField("next"));
            waitStatusOffset = unsafe.objectFieldOffset(Synchronizer.Node.class.getDeclaredField("waitStatus"));
        }catch (Exception e){
            throw new Error(e);
        }
    }

}
