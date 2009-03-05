package net.rubyeye.xmemcached;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.rubyeye.xmemcached.command.Command;
import net.rubyeye.xmemcached.utils.SimpleQueue;
import net.rubyeye.xmemcached.utils.UnLockQueue;
import net.spy.memcached.transcoders.CachedData;
import net.spy.memcached.transcoders.Transcoder;

import com.google.code.yanf4j.nio.Session;
import com.google.code.yanf4j.nio.impl.HandlerAdapter;

@SuppressWarnings("unchecked")
public class BatchMemcachedHandler extends HandlerAdapter {
	@SuppressWarnings("unchecked")
	private Transcoder transcoder;

	protected UnLockQueue<Command> executingCmds = new UnLockQueue<Command>();

	protected XMemcachedClient client;

	private static final int MAX_TRIES = 5;

	protected static final Log log = LogFactory.getLog(MemcachedHandler.class);

	private int connectTries = 0;
	ExecutorService executors = Executors.newFixedThreadPool(2);

	@Override
	public void onMessageSent(Session session, Object t) {
		try {
			Command sentCmd = (Command) t;
			if (sentCmd.getMergeCommands() == null) {
				executingCmds.push(sentCmd);
			} else {
				List<Command> mergeCmds = sentCmd.getMergeCommands();
				for (Command cmd : mergeCmds) {
					executingCmds.push(cmd);
				}

			}
		} catch (InterruptedException e) {

		}
	}

	@Override
	public void onReceive(final Session session, final Object obj) {
		final List<Command> recvCommands = (List<Command>) obj;
		final int size = recvCommands.size();
		if (size == 0)
			return;

		for (int i = 0; i < size; i++) {

			Command executingCmd = null;
			try {
				executingCmd = executingCmds.pop();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (executingCmd == null)
				return;
			Command recvCmd = recvCommands.get(i);
			if (executingCmd == null)
				return;
			if (recvCmd.getException() != null) {
				executingCmd.setException(recvCmd.getException());
				executingCmd.getLatch().countDown();
			} else {

				switch (executingCmd.getCommandType()) {
				case EXCEPTION:
				case GET_MANY:
					processGetManyCommand(recvCmd, executingCmd);
					break;
				case GET_ONE:
					processGetOneCommand(session, recvCmd, executingCmd);
					break;
				case SET:
				case ADD:
				case REPLACE:
				case DELETE:
				case INCR:
				case DECR:
				case VERSION:
					processCommand(recvCmd, executingCmd);
					break;
				}
			}
		}

	}

	private void processCommand(Command recvCmd, Command executingCmd) {
		executingCmd.setResult(recvCmd.getResult());
		executingCmd.getLatch().countDown();
	}

	@Override
	public void onSessionClosed(Session session) {
		log.warn("session close");
		reconnect();
	}

	protected void reconnect() {
		if (!this.client.isShutdown()) {
			if (this.connectTries < MAX_TRIES) {
				this.executingCmds.getLock().lock();
				try {
					this.connectTries++;
					log.warn("Try to reconnect the server,It had try "
							+ this.connectTries + " times");

					client.getConnector().reconnect();
					this.executingCmds.clear();
				} catch (IOException e) {
					log.error("reconnect error", e);
				} finally {
					this.executingCmds.getLock().unlock();
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void processGetOneCommand(Session session, Command recvCmd,
			Command executingCmd) {
		int mergCount = executingCmd.getMergeCount();
		// �����
		if (recvCmd.getKey() == null) {
			if (mergCount < 0) {
				// ����δ�ϲ�
				executingCmd.setResult(null);
				executingCmd.getLatch().countDown();
			} else {
				int i = 0;
				try {
					Command nextCommand = executingCmd;
					while (i < mergCount) {
						nextCommand.setResult(null);
						nextCommand.getLatch().countDown(); // ֪ͨ
						i++;
						if (i < mergCount)
							nextCommand = this.executingCmds.pop();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		} else {
			List<CachedData> datas = (List<CachedData>) recvCmd.getResult();
			List<String> keys = (List<String>) recvCmd.getKey();

			if (mergCount < 0) {
				if (!executingCmd.getKey().equals(keys.get(0)))
					reconnect(); // ����l��
				executingCmd.setResult(transcoder.decode(datas.get(0)));
				executingCmd.getLatch().countDown();
			} else {
				int i = 0;
				try {
					Command nextCommand = executingCmd;
					while (i < mergCount) {
						int index = keys.indexOf(nextCommand.getKey());
						if (index >= 0) {
							nextCommand.setResult(transcoder.decode(datas
									.get(index)));
						} else
							nextCommand.setResult(null);
						nextCommand.getLatch().countDown();
						i++;
						if (i < mergCount)
							nextCommand = this.executingCmds.pop();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

	}

	@SuppressWarnings("unchecked")
	private void processGetManyCommand(Command recvCmd, Command executingCmd) {
		if (recvCmd.getKey() == null) {
			executingCmd.setResult(null);
		} else {
			List<CachedData> datas = (List<CachedData>) recvCmd.getResult();
			List<String> keys = (List<String>) recvCmd.getKey();
			Map<String, Object> result = new HashMap<String, Object>();
			int len = keys.size();
			for (int i = 0; i < len; i++) {
				result.put(keys.get(i), transcoder.decode(datas.get(i)));
			}
			executingCmd.setResult(result);
		}
		executingCmd.getLatch().countDown();
	}

	@SuppressWarnings("unchecked")
	public BatchMemcachedHandler(Transcoder transcoder, XMemcachedClient client) {
		super();
		this.transcoder = transcoder;
		this.client = client;
	}

}