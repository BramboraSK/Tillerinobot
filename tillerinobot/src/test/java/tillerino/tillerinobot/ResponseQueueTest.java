package tillerino.tillerinobot;

import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;

import javax.persistence.EntityManagerFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.pircbotx.Channel;
import org.pircbotx.User;
import org.pircbotx.UserChannelDao;
import org.pircbotx.output.OutputUser;

import tillerino.tillerinobot.BotRunnerImpl.CloseableBot;
import tillerino.tillerinobot.ResponseQueue.IRCBotUser;
import tillerino.tillerinobot.data.util.ThreadLocalAutoCommittingEntityManager;
import tillerino.tillerinobot.rest.BotInfoService.BotInfo;
import tillerino.tillerinobot.websocket.LiveActivityEndpoint;

@RunWith(MockitoJUnitRunner.class)
public class ResponseQueueTest {
	@Mock
	LiveActivityEndpoint liveActivity;

	@Mock
	ExecutorService exec;

	@Mock
	Pinger pinger;

	@Mock
	EntityManagerFactory emf;

	@Mock
	ThreadLocalAutoCommittingEntityManager em;

	@Mock
	BotInfo botInfo;

	@Mock
	RateLimiter rateLimiter;

	@InjectMocks
	ResponseQueue queue;

	@Mock
	CloseableBot bot;

	@Mock
	UserChannelDao<User, Channel> userChannelDao;

	@Mock
	User pircBotXuser;

	@Mock
	OutputUser outputUser;

	@Before
	public void before() {
		queue.setBot(bot);
		when(bot.getUserChannelDao()).thenReturn(userChannelDao);
		when(userChannelDao.getUser("user")).thenReturn(pircBotXuser);
		when(pircBotXuser.send()).thenReturn(outputUser);
	}

	@Test
	public void testMessage() throws Exception {
		queue.queueResponse(new CommandHandler.Success("abc"), new IRCBotUser("user", 12345, 6789));
		queue.loop();
		verify(outputUser).message("abc");
		verify(liveActivity).propagateSentMessage("user", 6789l);
	}

	@Test
	public void testAction() throws Exception {
		queue.queueResponse(new CommandHandler.Action("abc"), new IRCBotUser("user", 12345, 6789));
		queue.loop();
		verify(outputUser).action("abc");
		verify(liveActivity).propagateSentMessage("user", 6789l);
	}
}
