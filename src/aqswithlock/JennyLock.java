package aqswithlock;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * @author james
 */
public class JennyLock {
    private static Sync sync = new Sync();

    static final class Sync extends Synchronizer{

        @Override
        public boolean tryAcquire(int arg){
            return nonfairTryAcquire(arg);
        }

        public void runTiredLess(int arg){
            Node n = new Node(Thread.currentThread(), Node.EXCLUSIVE);
            addWaiter(n);
            for(;;){
                if(acquireQueued(n, arg)){
                    setExclusiveThread(Thread.currentThread());
                    return ;
                }
            }
        }

        public boolean isHeldExclusive(){
            return true;
        }

        private boolean isCurrentThreadHeld(){
            return getExclusiveThread()==Thread.currentThread();
        }

        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
            }
            return false;
        }

        @Override
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                free = true;
                setExclusiveThread(null);
            }
            setState(c);
            return free;
        }

        public ConditionObject getCondition(){
            return new ConditionObject();
        }

    }

    public void lock(){
        if(sync.compareAndSetState(0,1)){
            sync.setExclusiveThread(Thread.currentThread());
            return ;
        }else{
            sync.acquire(1);
        }
    }

    public void unlock(){
        int state = sync.getState();
        for(;;){
            if(sync.release(1)){
                return;
            }
        }
    }

    public boolean isExclusive(){
        return sync.isCurrentThreadHeld();
    }

    public Synchronizer.ConditionObject getCondition(){
        return sync.getCondition();
    }

}

class MyThread implements Runnable{

    JennyLock myLock = new JennyLock();
    @Override
    public void run() {
        try {
            myLock.lock();
            TimeUnit.SECONDS.sleep(1);
            System.out.println(Thread.currentThread().getName()+"\t"+"正在执行..."+new Date());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            myLock.unlock();
        }

    }
}

class Test{

    static int a=0;

    public static JennyLock jennyLock = new JennyLock();

    public static Synchronizer.ConditionObject addCondition = jennyLock.getCondition();
    public static Synchronizer.ConditionObject subCondition = jennyLock.getCondition();

    public static void main(String[] args) {
        for(int i=0;i<1;i++){

            Thread tt = new Thread(
                    new Runnable() {

                        @Override
                        public void run() {
                            for(int i=0;i<250;i++){

                                jennyLock.lock();
                                if(a==0){
                                    addCondition.signal();
                                    try {
                                        subCondition.await();
                                    }catch (InterruptedException e){

                                    }
                                }
                                a--;
                                System.out.println(a);
                                if(a==0){
                                    addCondition.signal();
                                }
                                jennyLock.unlock();
                            }
                        }
                    });
            tt.start();
        }

        Thread t = new Thread(
                new Runnable() {

                    @Override
                    public void run() {
                        for(int i=0;i<250;i++){

                            jennyLock.lock();
                            a++;
                            System.out.println(a);
                            if(a==5){
                                subCondition.signal();
                                try{
                                    addCondition.await();
                                }catch (InterruptedException e){

                                }
                            }
                            jennyLock.unlock();
                        }
                    }
                });
        t.start();
    }
}


