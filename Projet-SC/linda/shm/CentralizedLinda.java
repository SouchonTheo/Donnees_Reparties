package linda.shm;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import linda.Linda;
import linda.Tuple;
import linda.Callback;

/** Shared memory implementation of Linda. */
public class CentralizedLinda implements Linda {

	private Vector<Tuple> listTuples;
	private Map<Tuple, Map<eventMode, Vector<Callback>>> callbacksRegistered;
	private ReentrantLock monitor;
	private Vector<Condition> readConditions;
	private Vector<Condition> takeConditions;
	private Condition wait;
	private int nbReadWaiting;
	private int nbTakeWaiting;

	public CentralizedLinda() {
		listTuples = new Vector<Tuple>();
		callbacksRegistered = new HashMap<Tuple, Map<Linda.eventMode, Vector<Callback>>>();
		monitor = new ReentrantLock();
		readConditions = new Vector<Condition>();
		takeConditions = new Vector<Condition>();
		wait = monitor.newCondition();
		nbReadWaiting = 0;
		nbTakeWaiting = 0;
	}

	private void addCallback(Tuple tupleTemplate, Linda.eventMode mode, Callback callback) {
		Map<Linda.eventMode, Vector<Callback>> mapEventMode = this.callbacksRegistered.get(tupleTemplate);
		Vector<Callback> vectorCallback = new Vector<Callback>();
		if (mapEventMode == null) {
			mapEventMode = new HashMap<Linda.eventMode, Vector<Callback>>();
			vectorCallback.add(callback);
			mapEventMode.put(mode, vectorCallback);
		} else {
			if (mapEventMode.containsKey(mode)) {
				vectorCallback = mapEventMode.get(mode);
			}
			vectorCallback.add(callback);
		}
		mapEventMode.put(mode, vectorCallback);
		this.callbacksRegistered.put(tupleTemplate, mapEventMode);
	}

	private void removeCallback(Tuple tupleExact, Linda.eventMode mode, Callback callback) {
		Map<Linda.eventMode, Vector<Callback>> mapEventMode = this.callbacksRegistered.get(tupleExact);
		Vector<Callback> vectorCallback = mapEventMode.get(mode);
		vectorCallback.remove(callback);
		mapEventMode.put(mode, vectorCallback);
		this.callbacksRegistered.put(tupleExact, mapEventMode);
	}

	
	private void CheckCallbacksRead(Tuple tupleExact) {
		for (Tuple tupleTemplate : this.callbacksRegistered.keySet()) {
			if (tupleTemplate.contains(tupleExact)){
				Map<Linda.eventMode, Vector<Callback>> mapEventMode = this.callbacksRegistered.get(tupleTemplate);
				if (mapEventMode.containsKey(eventMode.READ)) {
					Vector<Callback> vectorCallback = mapEventMode.get(eventMode.READ);
					int taille = vectorCallback.size();
					for (int i = 0; i < taille; i++) {
						Callback c = vectorCallback.get(i);
						removeCallback(tupleTemplate, eventMode.READ, c);
						c.call(tupleExact);
					}
				}
			}
		}
		this.wait.signal();
	}


	private void CheckCallbacksTake(Tuple tupleExact) {
		for (Tuple tupleTemplate : this.callbacksRegistered.keySet()) {
			if (tupleTemplate.contains(tupleExact)){
				Map<Linda.eventMode, Vector<Callback>> mapEventMode = this.callbacksRegistered.get(tupleTemplate);
				if (mapEventMode.containsKey(eventMode.TAKE)) {
					Vector<Callback> vectorCallback = mapEventMode.get(eventMode.TAKE);
					Callback c = vectorCallback.get(0);
					removeCallback(tupleTemplate, eventMode.TAKE, c);
					this.listTuples.remove(tupleExact);
					c.call(tupleExact);
				}
			}
		}
		this.wait.signal();
	}


	@Override
	public void write(Tuple t) {
		monitor.lock();
		if ((t != null) && (t.get(0) != null) && (t.get(1) != null)) {
			this.listTuples.add(t);
			// On vérifie les read en premiers
			if (this.nbReadWaiting > 0) {
				int size = this.readConditions.size();
				for (int i = 0 ; i < size ; i++) {
					this.readConditions.get(0).signal();
					this.readConditions.remove(0);
					try {// On passe la main au read
						this.wait.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} // Puis tous les callbacks en read
			for (Tuple tuple : this.callbacksRegistered.keySet()) {
				if (t.matches(tuple)) {
					CheckCallbacksRead(t);
					try {
						wait.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} // Ensuite tous les callbacks en take
			for (Tuple tuple : this.callbacksRegistered.keySet()) {
				if (t.matches(tuple)) {
					CheckCallbacksTake(t);
					try {
						wait.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} // Et enfin les take
			if ((this.nbReadWaiting == 0) && (this.nbTakeWaiting > 0)) {
				int size = this.takeConditions.size();
				for (int i = 0 ; i < size ; i++) {
					this.takeConditions.get(0).signal();
					this.takeConditions.remove(0);
				}
			}
		} else {
			// On peut pas rajouter null à notre espace de tuple
			throw new IllegalStateException();
		}
		monitor.unlock();
	}

	@Override
	public Tuple take(Tuple template) {
		monitor.lock();
		Tuple ret = null;
		boolean continueLoop = true;
		while (continueLoop) {
			int i = this.listTuples.size();
			Tuple t = null;
			while (ret == null && i > 0) {
				t = this.listTuples.get(i-1);
				if (t.matches(template)) {
					this.listTuples.remove(t);
					ret = t;
					continueLoop = false;
				}
				i--;
			}
			if (continueLoop) {
				try {
					this.nbTakeWaiting++;
					Condition takeCondition = monitor.newCondition();
					int size = this.takeConditions.size();
					this.takeConditions.add(takeCondition);
					this.takeConditions.get(size).await();
					this.nbTakeWaiting--;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		monitor.unlock();
		return ret;
	}

	@Override
	public Tuple read(Tuple template) {
		monitor.lock();
		Tuple ret = null;
		boolean continueLoop = true;
		while (continueLoop) {
			int i = this.listTuples.size();
			Tuple t = null;
			while (ret == null && i > 0) {
				t = this.listTuples.get(i-1);
				if (t.matches(template)) {
					ret = t;
					continueLoop = false;
				}
				i--;
			}
			if (continueLoop) {
				try {
					this.nbReadWaiting++;
					this.wait.signal();
					Condition readCondition = monitor.newCondition();
					int size = this.readConditions.size();
					this.readConditions.add(readCondition);
					this.readConditions.get(size).await();
					this.nbReadWaiting--;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		if (this.nbTakeWaiting > 0 && this.nbReadWaiting == 0) {
			this.takeConditions.get(0).signal();
			this.takeConditions.remove(0);
		}
		this.wait.signal();
		monitor.unlock();
		return ret;
	}

	@Override
	public Tuple tryTake(Tuple template) {
		monitor.lock();
		Tuple ret = null;
		int i = this.listTuples.size() - 1;
		Tuple t = null;
		while (ret == null && i > 0) {
			t = this.listTuples.get(i);
			if (t.matches(template)) {
				this.listTuples.remove(t);
				ret = t;
			}
			i--;
		}
		monitor.unlock();
		return ret;
	}

	@Override
	public Tuple tryRead(Tuple template) {
		monitor.lock();
		Tuple ret = null;
		int i = this.listTuples.size() - 1;
		Tuple t = null;
		while (ret == null && i > 0) {
			t = this.listTuples.get(i);
			if (t.matches(template)) {
				ret = t;
			}
			i--;
		}
		monitor.unlock();
		return ret;
	}

	@Override
	public Collection<Tuple> takeAll(Tuple template) {
		monitor.lock();
		Collection<Tuple> collectionTuples = new Vector<Tuple>();
		for (Tuple t : this.listTuples) {
			if (t.matches(template)) {
				collectionTuples.add(t);
				this.listTuples.remove(t);
			}
		}
		monitor.unlock();
		return collectionTuples;
	}

	@Override
	public Collection<Tuple> readAll(Tuple template) {
		monitor.lock();
		Collection<Tuple> collectionTuples = new Vector<Tuple>();
		for (Tuple t : this.listTuples) {
			if (t.matches(template)) {
				collectionTuples.add(t);
			}
		}
		monitor.unlock();
		return collectionTuples;
	}

	@Override
	public void eventRegister(eventMode mode, eventTiming timing, Tuple template, Callback callback) {
		monitor.lock();
		if (timing.equals(Linda.eventTiming.IMMEDIATE)) {
			Tuple t = null;
			if (mode.equals(Linda.eventMode.READ)) {
				t = tryRead(template);
			} else {
				t = tryTake(template);
			}
			if (t != null) {
				callback.call(t);
			} else {
				addCallback(template, mode, callback);
			}
		} else {
			addCallback(template, mode, callback);
		}
		monitor.unlock();
	}

	@Override
	public void debug(String prefix) {
		System.err.println(prefix + " On entre dans debug !");
	}

}
