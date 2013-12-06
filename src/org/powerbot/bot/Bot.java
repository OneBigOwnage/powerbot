package org.powerbot.bot;

import java.applet.Applet;
import java.awt.Canvas;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.powerbot.Configuration;
import org.powerbot.bot.loader.Crawler;
import org.powerbot.bot.loader.GameLoader;
import org.powerbot.bot.loader.GameStub;
import org.powerbot.bot.loader.NRSLoader;
import org.powerbot.bot.loader.transform.TransformSpec;
import org.powerbot.client.Client;
import org.powerbot.client.Constants;
import org.powerbot.event.EventMulticaster;
import org.powerbot.gui.BotChrome;
import org.powerbot.script.internal.InputHandler;
import org.powerbot.script.internal.ScriptController;
import org.powerbot.script.lang.Stoppable;
import org.powerbot.script.methods.MethodContext;
import org.powerbot.script.util.Condition;
import org.powerbot.service.GameAccounts;
import org.powerbot.service.scripts.ScriptBundle;

/**
 * @author Timer
 */
public final class Bot implements Runnable, Stoppable {
	public static final Logger log = Logger.getLogger(Bot.class.getName());
	private MethodContext ctx;
	public final ThreadGroup threadGroup;
	private final EventMulticaster multicaster;
	private Applet applet;
	public AtomicBoolean refreshing;
	private Constants constants;
	private GameAccounts.Account account;
	private InputHandler inputHandler;
	private ScriptController controller;
	private boolean stopping;
	private AtomicBoolean initiated;

	public Bot() {
		applet = null;
		threadGroup = new ThreadGroup(Bot.class.getName() + "@" + Integer.toHexString(hashCode()) + "-game");
		multicaster = new EventMulticaster();
		account = null;
		new Thread(threadGroup, multicaster, multicaster.getClass().getName()).start();
		refreshing = new AtomicBoolean(false);
		initiated = new AtomicBoolean(false);
		ctx = new MethodContext(this);
	}

	public void run() {
		start();
	}

	public void start() {
		log.info("Loading bot");
		final Crawler crawler = new Crawler();
		if (!crawler.crawl()) {
			log.severe("Failed to load game");
			return;
		}

		final GameLoader game = new GameLoader(crawler);
		final ClassLoader classLoader = game.call();
		if (classLoader == null) {
			log.severe("Failed to load game");
			return;
		}

		initiated.set(false);
		final NRSLoader loader = new NRSLoader(game, classLoader);
		loader.setCallback(new Runnable() {
			@Override
			public void run() {
				sequence(loader);
			}
		});
		new Thread(threadGroup, loader).start();
	}

	private void sequence(final NRSLoader loader) {
		log.info("Loading game (" + loader.getPackHash().substring(0, 6) + ")");
		this.applet = loader.getApplet();
		final Crawler crawler = loader.getGameLoader().getCrawler();
		final GameStub stub = new GameStub(crawler.parameters, crawler.archive);
		applet.setStub(stub);
		applet.setSize(BotChrome.PANEL_MIN_WIDTH, BotChrome.PANEL_MIN_HEIGHT);
		applet.init();
		if (loader.getBridge().getTransformSpec() == null) {
			final Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					for (; ; ) {
						log.warning("Downloading update \u2014 please wait");
						try {
							loader.upload(loader.getPackHash());
							break;
						} catch (IOException ignored) {
						} catch (NRSLoader.PendingException p) {
							final int d = p.getDelay() / 1000;
							log.warning("Your update is being processed, trying again in " + (d < 60 ? d + " seconds" : (int) Math.ceil(d / 60) + " minutes"));
							try {
								Thread.sleep(p.getDelay());
							} catch (final InterruptedException ignored) {
								break;
							}
						}
					}
				}
			});
			thread.setDaemon(false);
			thread.setPriority(Thread.MAX_PRIORITY);
			thread.start();
			return;
		}
		setClient((Client) loader.getClient(), loader.getBridge().getTransformSpec());
		applet.start();

		BotChrome.getInstance().display(this);
	}

	@Override
	public boolean isStopping() {
		return stopping;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop() {
		stopping = true;
		log.info("Unloading environment");
		for (final Stoppable module : new Stoppable[]{controller, multicaster}) {
			if (module != null) {
				module.stop();
			}
		}
		new Thread(threadGroup, new Runnable() {
			@Override
			public void run() {
				terminateApplet();
			}
		}).start();
	}

	public void initiate() {
		if (!initiated.compareAndSet(false, true)) {
			return;
		}

		final String jre = System.getProperty("java.version");
		final boolean java6 = jre != null && jre.startsWith("1.6");

		if (!(java6 && Configuration.OS == Configuration.OperatingSystem.MAC)) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					Condition.wait(new Callable<Boolean>() {
						@Override
						public Boolean call() throws Exception {
							return ctx.getClient().getKeyboard() != null;
						}
					});
					ctx.keyboard.send("s");
				}
			}).start();
		}
	}

	void terminateApplet() {
		if (applet != null) {
			log.fine("Shutting down applet");
			applet.stop();
			applet.destroy();
			applet = null;
			this.ctx.setClient(null);
		}
	}

	public synchronized void startScript(final ScriptBundle bundle, final int timeout) {
		SelectiveEventQueue.getInstance().setBlocking(true);
		controller = new ScriptController(ctx, multicaster, bundle, timeout);
		controller.run();
	}

	public synchronized void stopScript() {
		if (controller != null) {
			controller.stop();
		}
		controller = null;
	}

	public Applet getApplet() {
		return applet;
	}

	public MethodContext getMethodContext() {
		return ctx;
	}

	public Constants getConstants() {
		return constants;
	}

	public InputHandler getInputHandler() {
		return inputHandler;
	}

	private void setClient(final Client client, final TransformSpec spec) {
		this.ctx.setClient(client);
		client.setCallback(new AbstractCallback(this));
		constants = new Constants(spec.constants);
		inputHandler = new InputHandler(applet, client);
		ctx.menu.register();
	}

	public Canvas getCanvas() {
		final Client client = ctx.getClient();
		return client != null ? client.getCanvas() : null;
	}

	public EventMulticaster getEventMulticaster() {
		return multicaster;
	}

	public GameAccounts.Account getAccount() {
		return account;
	}

	public void setAccount(final GameAccounts.Account account) {
		this.account = account;
	}

	public ScriptController getScriptController() {
		return controller;
	}
}