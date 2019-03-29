package tillerino.tillerinobot;
import static org.junit.Assert.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.CheckForNull;
import javax.persistence.EntityManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.managers.ListenerManager;
import org.tillerino.osuApiModel.OsuApiUser;
import org.tillerino.osuApiModel.types.OsuName;
import org.tillerino.osuApiModel.types.UserId;

import com.google.common.cache.LoadingCache;

import tillerino.tillerinobot.CommandHandler.AsyncTask;
import tillerino.tillerinobot.osutrack.TestOsutrackDownloader;
import tillerino.tillerinobot.recommendations.BareRecommendation;
import tillerino.tillerinobot.recommendations.Model;
import tillerino.tillerinobot.recommendations.RecommendationRequestParser;
import tillerino.tillerinobot.recommendations.RecommendationsManager;
import tillerino.tillerinobot.rest.BotInfoService.BotInfo;
import tillerino.tillerinobot.testutil.SynchronousExecutorServiceRule;
import tillerino.tillerinobot.websocket.LiveActivityEndpoint;

public class IRCBotTest extends AbstractDatabaseTest {
	private User user(String nick) {
		User user = mock(User.class);
		when(user.getNick()).thenReturn(nick);
		return user;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected ActionEvent action(String nick, String action) {
		return new ActionEvent(pircBotX, user(nick), null, action);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected PrivateMessageEvent message(String nick, String action) {
		return new PrivateMessageEvent(pircBotX, user(nick), action);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected JoinEvent join(String nick) {
		return new JoinEvent(pircBotX, mock(Channel.class), user(nick));
	}

	@Rule
	public SynchronousExecutorServiceRule exec = new SynchronousExecutorServiceRule();

	@Mock
	PircBotX pircBotX;

	@Mock
	Configuration config;

	UserDataManager userDataManager;

	RateLimiter rateLimiter = new RateLimiter();

	@Spy
	TestBackend backend = new TestBackend(false);

	IrcNameResolver resolver;

	RecommendationsManager recommendationsManager;

	@Mock
	LiveActivityEndpoint liveActivity;	

	/**
	 * Contains the messages and actions sent by the bot. At the end of each
	 * test, it must be empty or the test fails.
	 */
	InstantResponseQueue queue;

	@CheckForNull
	protected LoadingCache<String, IRCBot.TimingSemaphore> perUserLock;

	@Before
	public void initMocks() {
		MockitoAnnotations.initMocks(this);

		resolver = new IrcNameResolver(userNameMappingRepo, backend);

		recommendationsManager = spy(new RecommendationsManager(backend, recommendationsRepo, em, new RecommendationRequestParser(backend)));
		when(pircBotX.getConfiguration()).thenReturn(config);
		when(config.getListenerManager()).thenReturn(mock(ListenerManager.class));
		queue = new InstantResponseQueue(exec, null, emf, em, null, rateLimiter, liveActivity);
	}

	@After
	public void tearDown() {
		queue.assertEmpty();
		if (userDataManager != null) {
			userDataManager.tidyUp(false);
		}
		if (perUserLock != null) {
			perUserLock.asMap().forEach((username, semaphore) -> {
				assertTrue("Semaphore for " + username + " is free", semaphore.tryAcquire());
			});
		}
	}
	
	IRCBot getTestBot(BotBackend backend) {
		RecommendationsManager recMan;
		if (backend == this.backend) {
			recMan = this.recommendationsManager;
		} else {
			recMan = spy(new RecommendationsManager(backend,
					recommendationsRepo, em, new RecommendationRequestParser(backend)));
		}

		IRCBot ircBot = new IRCBot(backend, recMan, new BotInfo(),
				userDataManager = new UserDataManager(backend, emf, em, userDataRepository), mock(Pinger.class), false, em,
				emf, resolver, new TestOsutrackDownloader(), exec, rateLimiter, liveActivity, queue) {{
			IRCBotTest.this.perUserLock = this.perUserLock;
		}};
		return ircBot;
	}
	
	@Test
	public void testVersionMessage() throws Exception {
		IRCBot bot = getTestBot(backend);
		
		backend.hintUser("user", false, 0, 0);
		backend.setLastVisitedVersion("user", 0);
		
		bot.onEvent(message("user", "!recommend"));
		assertEquals(IRCBot.VERSION_MESSAGE, queue.nextMessage());
		verify(backend, times(1)).setLastVisitedVersion(anyString(), eq(IRCBot.CURRENT_VERSION));
		// pop recommendation
		queue.nextMessage();

		bot.onEvent(message("user", "!recommend"));
		assertNotEquals(IRCBot.VERSION_MESSAGE, queue.nextMessage());
	}
	
	@Test
	public void testWrongStrings() throws Exception {
		IRCBot bot = getTestBot(backend);
		
		backend.hintUser("user", false, 100, 1000);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());

		bot.onEvent(message("user", "!recommend"));
		assertThat(queue.nextMessage()).contains("http://osu.ppy.sh");
		
		bot.onEvent(message("user", "!r"));
		assertThat(queue.nextMessage()).contains("http://osu.ppy.sh");
		
		bot.onEvent(message("user", "!recccomend"));
		assertThat(queue.nextMessage()).contains("!help");
		
		bot.onEvent(message("user", "!halp"));
		assertThat(queue.nextMessage()).contains("twitter");
		
		bot.onEvent(message("user", "!feq"));
		assertThat(queue.nextMessage()).contains("FAQ");
	}

	/**
	 * Just checks that nothing crashes without an actual command.
	 */
	@Test
	public void testNoCommand() throws Exception {
		IRCBot bot = getTestBot(backend);
		
		backend.hintUser("user", false, 100, 1000);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());

		bot.onEvent(message("user", "no command"));
	}

	@Test
	public void testWelcomeIfDonator() throws Exception {
		BotBackend backend = mock(BotBackend.class);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());
		
		OsuApiUser osuApiUser = mock(OsuApiUser.class);
		when(osuApiUser.getUserName()).thenReturn("TheDonator");

		this.backend.hintUser("TheDonator", true, 1, 1);
		int userid = resolver.getIDByUserName("TheDonator");
		when(backend.getUser(eq(userid), anyLong())).thenReturn(osuApiUser);
		when(backend.getDonator(any(OsuApiUser.class))).thenReturn(1);
		
		IRCBot bot = getTestBot(backend);
		
		when(backend.getLastActivity(any(OsuApiUser.class))).thenReturn(System.currentTimeMillis() - 1000);
		bot.onEvent(join("TheDonator"));
		assertThat(queue.nextMessage()).startsWith("beep boop");
		queue.assertEmpty();

		when(backend.getLastActivity(any(OsuApiUser.class))).thenReturn(System.currentTimeMillis() - 10 * 60 * 1000);
		bot.onEvent(join("TheDonator"));
		assertEquals("Welcome back, TheDonator.", queue.nextMessage());
		queue.assertEmpty();
		
		when(backend.getLastActivity(any(OsuApiUser.class))).thenReturn(System.currentTimeMillis() - 2l * 24 * 60 * 60 * 1000);
		bot.onEvent(join("TheDonator"));
		assertThat(queue.nextMessage()).startsWith("TheDonator, ");
		queue.assertEmpty();
		
		when(backend.getLastActivity(any(OsuApiUser.class))).thenReturn(System.currentTimeMillis() - 8l * 24 * 60 * 60 * 1000);
		bot.onEvent(join("TheDonator"));
		assertThat(queue.nextMessage()).contains("TheDonator");
		assertThat(queue.nextMessage()).contains("so long");
		queue.nextMessage();
	}

	@Test
	public void testHugs() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());

		backend.hintUser("donator", true, 0, 0);

		bot.onEvent(message("donator", "I need a hug :("));

		assertEquals("Come here, you!", queue.nextMessage());
		assertEquals("hugs donator", queue.nextAction());
	}
	
	@Test
	public void testComplaint() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());

		backend.hintUser("user", false, 0, 1000);

		bot.onEvent(message("user", "!r"));
		queue.nextMessage();

		bot.onEvent(message("user", "!complain"));
		assertThat(queue.nextMessage()).contains("complaint");
	}

	@Test
	public void testResetHandler() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());

		backend.hintUser("user", false, 0, 1000);
		
		bot.onEvent(message("user", "!reset"));

		Integer id = resolver.resolveIRCName("user");

		verify(recommendationsManager).forgetRecommendations(id);
	}

	@Test
	public void testProperEmptySamplerHandling() throws Exception {
		TestBackend backend = new TestBackend(false) {
			@Override
			public Collection<BareRecommendation> loadRecommendations(
					int userid, Collection<Integer> exclude, Model model,
					boolean nomod, long requestMods) throws SQLException,
					IOException, UserException {
				if (exclude.contains(1)) {
					return Collections.emptyList();
				}

				BareRecommendation bareRecommendation = mock(BareRecommendation.class);
				when(bareRecommendation.getProbability()).thenReturn(1d);
				when(bareRecommendation.getBeatmapId()).thenReturn(1);
				return Arrays.asList(bareRecommendation);
			}

			@Override
			public int getLastVisitedVersion(String nick) throws SQLException, UserException {
				return IRCBot.CURRENT_VERSION;
			}
		};
		IRCBot bot = getTestBot(backend);

		backend.hintUser("user", false, 0, 1000);

		bot.onEvent(message("user", "!r"));
		assertThat(queue.nextMessage()).contains("/b/1");

		bot.onEvent(message("user", "!r"));
		assertThat(queue.nextMessage()).contains("!reset");

		bot.onEvent(message("user", "!r"));
		assertThat(queue.nextMessage()).contains("!reset");

		bot.onEvent(message("user", "!reset"));

		bot.onEvent(message("user", "!r"));
		assertThat(queue.nextMessage()).contains("/b/1");
	}

	@Test
	public void testGammaDefault() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());
		backend.hintUser("user", false, 75000, 1000);

		bot.onEvent(message("user", "!R"));

		verify(backend).loadRecommendations(anyInt(),
				anyCollectionOf(Integer.class),
				eq(Model.GAMMA), anyBoolean(), anyLong());
		queue.nextMessage();
	}

	@Test
	public void testGammaDefaultSub100k() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());
		backend.hintUser("user", false, 125000, 1000);

		bot.onEvent(message("user", "!R"));

		verify(backend).loadRecommendations(anyInt(),
				anyCollectionOf(Integer.class),
				eq(Model.GAMMA), anyBoolean(), anyLong());
		queue.nextMessage();
	}

	@Test
	public void testOsutrack1() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());
		backend.hintUser("oliebol", false, 125000, 1000);

		bot.onEvent(message("oliebol", "!u"));
		assertThat(queue.nextMessage()).isEqualTo(
				"Rank: +0 (+0.00 pp) in 0 plays. | View detailed data on [https://ameobea.me/osutrack/user/oliebol osu!track].");
	}

	@Test
	public void testOsutrack2() throws Exception {
		IRCBot bot = getTestBot(backend);
		doReturn(IRCBot.CURRENT_VERSION).when(backend).getLastVisitedVersion(anyString());
		backend.hintUser("fartownik", false, 125000, 1000);

		bot.onEvent(message("fartownik", "!u"));
		assertThat(queue.nextMessage()).isEqualTo(
				"Rank: -3 (+26.25 pp) in 1568 plays. | View detailed data on [https://ameobea.me/osutrack/user/fartownik osu!track].");
		assertThat(queue.nextMessage()).isEqualTo(
				"2 new highscores:[https://osu.ppy.sh/b/768986 #7]: 414.06pp; [https://osu.ppy.sh/b/693195 #89]: 331.89pp; View your recent hiscores on [https://ameobea.me/osutrack/user/fartownik osu!track].");
	}

	@Test
	public void testAsyncTask() throws Exception {
		IRCBot bot = new IRCBot(null, null, null, null, null, false, em, emf, null, null, exec, null, liveActivity, queue);

		EntityManager targetEntityManager = em.getTargetEntityManager();

		AtomicBoolean executed = new AtomicBoolean();
		bot.sendResponse((AsyncTask) () -> {
			assertNotSame(targetEntityManager, em.getTargetEntityManager());
			executed.set(true);
		}, queue.hideEvent(join("whoever"), 1));
		assertTrue(executed.get());
	}

	@Test
	public void testAutomaticNameChangeRemapping() throws Exception {
		// override test backend because we need more control
		BotBackend backend = mock(BotBackend.class);
		resolver = new IrcNameResolver(userNameMappingRepo, backend);
		IRCBot bot = getTestBot(backend);

		when(backend.downloadUser("user1_old")).thenReturn(user(1, "user1 old"));
		when(backend.getUser(eq(1), anyLong())).thenReturn(user(1, "user1 old"));
		assertEquals(1, (int) bot.getUserOrThrow("user1_old").getUserId());

		// meanwhile, user 1 changed her name
		when(backend.downloadUser("user1_new")).thenReturn(user(1, "user1 new"));
		when(backend.getUser(eq(1), anyLong())).thenReturn(user(1, "user1 new"));
		// and user 2 hijacked her old name
		when(backend.downloadUser("user1_old")).thenReturn(user(2, "user1 old"));
		when(backend.getUser(eq(2), anyLong())).thenReturn(user(2, "user1 new"));

		assertEquals(2, (int) bot.getUserOrThrow("user1_old").getUserId());
		assertEquals(1, (int) bot.getUserOrThrow("user1_new").getUserId());
	}

	@Test
	public void testLiveActivity() throws Exception {
		IRCBot bot = getTestBot(backend);
		bot.onEvent(message("user", "whatever"));
		verify(liveActivity).propagateReceivedMessage(eq("user"), anyLong());
	}

	OsuApiUser user(@UserId int id, @OsuName String name) {
		OsuApiUser user = new OsuApiUser();
		user.setUserId(id);
		user.setUserName(name);
		return user;
	}
}
