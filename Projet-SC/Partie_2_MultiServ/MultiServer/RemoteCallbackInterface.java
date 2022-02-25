package linda.MultiServer;

import java.rmi.Remote;
import java.rmi.RemoteException;

import linda.Tuple;

public interface RemoteCallbackInterface extends Remote {
    public void rCall(Tuple t) throws RemoteException;
}