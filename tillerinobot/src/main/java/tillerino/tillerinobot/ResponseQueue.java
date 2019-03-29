package tillerino.tillerinobot;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.persistence.EntityManagerFactory;

import org.pircbotx.PircBotX;
import org.pircbotx.hooks.types.GenericUserEvent;
import org.slf4j.MDC;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import tillerino.tillerinobot.BotBackend.IRCName;
import tillerino.tillerinobot.BotRunnerImpl.CloseableBot;
import tillerino.tillerinobot.CommandHandler.Action;
import tillerino.tillerinobot.CommandHandler.AsyncTask;
import tillerino.tillerinobot.CommandHandler.CleanUpTask;
import tillerino.tillerinobot.CommandHandler.Message;
import tillerino.tillerinobot.CommandHandler.Response;
import tillerino.tillerinobot.CommandHandler.ResponseList;
import tillerino.tillerinobot.CommandHandler.Success;
import tillerino.tillerinobot.CommandHandler.Task;
import tillerino.tillerinobot.data.util.ThreadLocalAutoCommittingEntityManager;
import tillerino.tillerinobot.handlers.RecommendHandler;
import tillerino.tillerinobot.rest.BotInfoService.BotInfo;
import tillerino.tillerinobot.utils.MdcUtils;
import tillerino.tillerinobot.utils.MdcUtils.MdcAttributes;
import tillerino.tillerinobot.utils.MdcUtils.MdcSnapshot;
import tillerino.tillerinobot.websocket.LiveActivityEndpoint;

@Singleton
@Slf4j
public class ResponseQueue implements Runnable {
	public static final String MDC_SUCCESS = "success";
	public static final String MCD_OSU_API_RATE_BLOCKED_TIME = "osuApiRateBlockedTime";
	public static final String MDC_DURATION = "duration";

	@Value
	static class IRCBotUser {
		/**
		 * the user's IRC nick, not their actual user name.
		 */
		@Getter(onMethod = @__(@IRCName))
		@IRCName String nick;

		@Getter
		long timestamp;

		@Getter
		long eventId;

		IRCBotUser(@IRCName String nick, long timestamp, long eventId) {
			super();
			this.nick = nick;
			this.timestamp = timestamp;
			this.eventId = eventId;
		}
	}


	@Value
	static class RequestResult {
		Response response;

		MdcSnapshot mdc;

		long startTime;

		@IRCName
		@Getter(onMethod = @__(@IRCName))
		String nick;

		long rateLimiterBlockedTime;

		long queueEnterTime = System.currentTimeMillis();

		long eventId;

		RequestResult(Response response, MdcSnapshot mdc, long startTime, @IRCName String nick, long rateLimiterBlockedTime, long eventId) {
			super();
			this.response = response;
			this.mdc = mdc;
			this.startTime = startTime;
			this.nick = nick;
			this.rateLimiterBlockedTime = rateLimiterBlockedTime;
			this.eventId = eventId;
		}
	}

	private final ExecutorService exec;

	private final Pinger pinger;

	private final EntityManagerFactory emf;

	private final ThreadLocalAutoCommittingEntityManager em;

	private final BotInfo botInfo;

	private final RateLimiter rateLimiter;

	private final AtomicReference<CloseableBot> bot = new AtomicReference<>();

	private final LiveActivityEndpoint liveActivity;

	protected final BlockingQueue<RequestResult> queue = new LinkedBlockingQueue<>();

	@Inject
	public ResponseQueue(@Named("tillerinobot.maintenance") ExecutorService exec, Pinger pinger, EntityManagerFactory emf,
			ThreadLocalAutoCommittingEntityManager em, BotInfo botInfo, RateLimiter rateLimiter, LiveActivityEndpoint liveActivity) {
		super();
		this.exec = exec;
		this.pinger = pinger;
		this.emf = emf;
		this.em = em;
		this.botInfo = botInfo;
		this.rateLimiter = rateLimiter;
		this.liveActivity = liveActivity;
	}

	public void queueResponse(Response response, IRCBotUser user) {
		queue.add(new RequestResult(response, MdcUtils.getSnapshot(), user.getTimestamp(), user.getNick(),
				rateLimiter.blockedTime(), user.getEventId()));
		botInfo.incrementQueueSize();
	}

	protected void handleResponse(Response response, RequestResult result) throws InterruptedException, IOException {
		if (response instanceof ResponseList) {
			boolean exception = false;
			for (Response r : ((ResponseList) response).responses) {
				// make sure that clean up tasks run under any circumstances
				if (exception && !(r instanceof CleanUpTask)) {
					continue;
				}
				try {
					handleResponse(r, result);
				} catch (IOException | RuntimeException e) {
					exception = true;
					log.error("Exception while handling response", e);
				}
			}
		}
		if (response instanceof Message) {
			message(((Message) response).getContent(), false, result);
		}
		if (response instanceof Success) {
			message(((Success) response).getContent(), true, result);
		}
		if (response instanceof Action) {
			action(((Action) response).getContent(), result);
		}
		if (response instanceof Task) {
			((Task) response).run();
		}
		if (response instanceof CleanUpTask) {
			((CleanUpTask) response).run();
		}
		if (response instanceof AsyncTask) {
			exec.submit(() -> {
				try (MdcAttributes mdc = result.getMdc().apply()) {
					em.setThreadLocalEntityManager(emf.createEntityManager());
					try {
						((AsyncTask) response).run();
					} finally {
						em.close();
					}
				}
			});
		}
	}

	protected void message(String msg, boolean success, RequestResult result) throws InterruptedException, IOException {
		try {
			pinger.ping((CloseableBot) waitForBot());

			try {
				waitForBot().getUserChannelDao().getUser(result.getNick()).send().message(msg);
			} catch (RuntimeException e) {
				if (e.getCause() instanceof InterruptedException) {
					// see org.pircbotx.output.OutputRaw.rawLine(String)
					throw (InterruptedException) e.getCause();
				}
				throw e;
			}
			liveActivity.propagateSentMessage(result.getNick(), result.getEventId());
			MDC.put(IRCBot.MDC_STATE, "sent");
			if (success) {
				MDC.put(ResponseQueue.MDC_DURATION, System.currentTimeMillis() - result.getStartTime() + "");
				MDC.put(ResponseQueue.MDC_SUCCESS, "true");
				MDC.put(ResponseQueue.MCD_OSU_API_RATE_BLOCKED_TIME, String.valueOf(result.getRateLimiterBlockedTime()));
				if (Objects.equals(MDC.get(IRCBot.MDC_HANDLER), RecommendHandler.MDC_FLAG)) {
					botInfo.setLastRecommendation(System.currentTimeMillis());
				}
			}
			log.debug("sent: " + msg);
			botInfo.setLastSentMessage(System.currentTimeMillis());
		} finally {
			MDC.remove(ResponseQueue.MDC_DURATION);
			MDC.remove(ResponseQueue.MDC_SUCCESS);
			MDC.remove(ResponseQueue.MCD_OSU_API_RATE_BLOCKED_TIME);
		}
	}

	protected void action(String msg, RequestResult result) throws InterruptedException, IOException {
		pinger.ping((CloseableBot) waitForBot());

		try {
			waitForBot().getUserChannelDao().getUser(result.getNick()).send().action(msg);
		} catch (RuntimeException e) {
			if (e.getCause() instanceof InterruptedException) {
				// see org.pircbotx.output.OutputRaw.rawLine(String)
				throw (InterruptedException) e.getCause();
			}
			throw e;
		}
		liveActivity.propagateSentMessage(result.getNick(), result.getEventId());
		MDC.put(IRCBot.MDC_STATE, "sent");
		log.debug("sent action: " + msg);
	}

	public void run() {
		for (;;) {
			try {
				loop();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} 
		}
	}

	/**
	 * Executes one loop in {@link #run()}. Extracted and visible for tests so we don't need an external test.
	 */
	void loop() throws InterruptedException {
		try {
			RequestResult response = queue.take();
			botInfo.decrementQueueSize();
			try (MdcAttributes mdc = response.getMdc().apply()) {
				handleResponse(response.getResponse(), response);
			}
		} catch (IOException | RuntimeException e) {
			log.error("Exception while handling response", e);
		}
	}

	private CloseableBot waitForBot() throws InterruptedException {
		for (;;) {
			CloseableBot b = bot.get();
			if (b != null) {
				return b;
			}
			Thread.sleep(100);
		}
	}

	@SuppressFBWarnings("TQ")
	public static IRCBotUser hideEvent(GenericUserEvent<? extends PircBotX> event, long eventId) {
		return new IRCBotUser(event.getUser().getNick(), event.getTimestamp(), eventId);
	}

	public void setBot(CloseableBot bot) {
		this.bot.set(bot);
	}
}
