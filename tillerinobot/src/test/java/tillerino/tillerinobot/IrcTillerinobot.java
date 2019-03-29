package tillerino.tillerinobot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;

import tillerino.tillerinobot.data.util.RepositoryModule;

/**
 * Will connect to an actual IRC server with completely fake data. Use system
 * properties to configure: -Dtillerinobot.irc.server=localhost
 * -Dtillerinobot.irc.port=6667 -Dtillerinobot.irc.nickname=Tillerinobot
 * -Dtillerinobot.irc.password=secret -Dtillerinobot.irc.autojoin=#osu 
 */
public class IrcTillerinobot extends AbstractModule {

	@Override
	protected void configure() {
		bind(BotRunner.class).to(BotRunnerImpl.class);
		install(new RepositoryModule());
		install(new AbstractDatabaseTest.CreateInMemoryDatabaseModule());
		install(new TillerinobotConfigurationModule());

		bind(Boolean.class).annotatedWith(Names.named("tillerinobot.ignore")).toInstance(false);
		bind(BotBackend.class).to(TestBackend.class).in(Singleton.class);
		bind(Boolean.class).annotatedWith(Names.named("tillerinobot.test.persistentBackend")).toInstance(true);

		bind(ExecutorService.class).annotatedWith(Names.named("tillerinobot.maintenance"))
				.toInstance(Executors.newFixedThreadPool(4, r -> {
					Thread thread = new Thread(r, "maintenance");
					thread.setDaemon(true);
					return thread;
				}));
	}

	public static void main(String[] args) {
		Injector injector = Guice.createInjector(new IrcTillerinobot());

		injector.getInstance(BotRunner.class).run();
	}
}
