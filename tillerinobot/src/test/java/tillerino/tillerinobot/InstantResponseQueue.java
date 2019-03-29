package tillerino.tillerinobot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import javax.inject.Inject;
import javax.persistence.EntityManagerFactory;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import tillerino.tillerinobot.CommandHandler.Response;
import tillerino.tillerinobot.data.util.ThreadLocalAutoCommittingEntityManager;
import tillerino.tillerinobot.rest.BotInfoService.BotInfo;

@Slf4j
public class InstantResponseQueue extends ResponseQueue {
	@Inject
	public InstantResponseQueue(ExecutorService exec, Pinger pinger, EntityManagerFactory emf,
			ThreadLocalAutoCommittingEntityManager em, BotInfo botInfo, RateLimiter rateLimiter) {
		super(exec, pinger, emf, em, botInfo, rateLimiter);
	}

	@Override
	public void queueResponse(Response response, IRCBotUser user) {
		RequestResult result = new RequestResult(response, () -> () -> { }, 0, user.getNick(), 0);
		try {
			handleResponse(response, result);
		} catch (InterruptedException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	private final BlockingQueue<String> actions = new LinkedBlockingQueue<>();
	private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

	@Override
	@SneakyThrows(InterruptedException.class)
	protected void action(String msg, RequestResult result) {
		log.debug("-> {} (action): {}", result.getNick(), msg);
		actions.put(msg);
	}

	@Override
	@SneakyThrows(InterruptedException.class)
	protected void message(String msg, boolean success, RequestResult result) {
		log.debug("-> {}: {}", result.getNick(), msg);
		messages.put(msg);
	}

	public void assertEmpty() {
		assertThat(messages).isEmpty();
		assertThat(actions).isEmpty();
	}

	public String nextMessage() {
		String message = messages.poll();
		if (message == null) {
			throw new NoSuchElementException("No message received");
		}
		return message;
	}

	public String nextAction() {
		String action = actions.poll();
		if (action == null) {
			throw new NoSuchElementException("No action received");
		}
		return action;
	}
}
