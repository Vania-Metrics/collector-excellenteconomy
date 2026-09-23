package fr.samflix.vaniametrics.module.excellenteconomy;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.Config;
import fr.samflix.vaniametrics.api.Counter;
import fr.samflix.vaniametrics.api.Gauge;
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.Platform;
import fr.samflix.vaniametrics.api.PlayerRef;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * ExcellentEconomy's multi-currency economy — by events, and by REFLECTION.
 *
 * <p>COMPLEMENTS the {@code essentials} module, which gives the TOTAL in circulation by reading
 * EssentialsX's already-computed cache. This one gives the MOVEMENT: who earns, who spends, how
 * much, in which currency. A total that doesn't move and a heavy flow don't describe the same
 * economy, and neither metric replaces the other.
 *
 * <p>WHY REFLECTION HERE, AND NOWHERE ELSE. ExcellentEconomy is compiled as
 * <b>class 69, i.e. Java 25</b> — verified in its jar. A javac targeting Java 21 refuses to READ
 * such a file, even for a plain signature:
 *
 * <pre>
 * bad class file: ExcellentEconomy-2.8.0.jar(…/ChangeBalanceEvent.class)
 *   class file has wrong version 69.0, should be 65.0
 * </pre>
 *
 * <p>The two clean options were compiling this module with {@code --release 25}, which forces a
 * JDK 25 to build the whole repo, or dropping it. Reflection is the third path: the module stays
 * compilable on the repo's JDK 21, and {@code registerEvent} accepts a class obtained at
 * runtime. The cost is that a signature change on NightExpress's side won't show up at compile
 * time — it will show up at startup, as a warning, and the module will stay silent instead of
 * failing to build.
 */
public final class EconomyCollector implements Collector, Listener {

	private static final String EVENT_CLASS =
			"su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent";

	private final Platform platform;
	private final Config config;

	private Counter transactions;
	private Counter flow;
	private Gauge playerBalance;
	private PlayerSeries series;

	/** The last known balance of each online player, kept up to date by events. */
	private final Map<String, Map<String, Double>> balances = new ConcurrentHashMap<>();

	private Method getPlayerMethod;
	private Method getCurrencyMethod;
	private Method getOldAmountMethod;
	private Method getNewAmountMethod;
	private Method getCurrencyIdMethod;

	public EconomyCollector(Platform platform, Config config) {
		this.platform = platform;
		this.config = config;
	}

	@Override
	public String name() {
		return "economy";
	}

	@Override
	public String source() {
		return "ExcellentEconomy";
	}

	@Override
	public void declare(MetricRegistry r) {
		transactions = r.counter("economy_transactions_total",
				"Account movements. type = deposit|withdraw.", "currency", "type");
		flow = r.counter("economy_flow_total",
				"Amounts moved, as absolute values. direction = in|out. The ratio of the two "
						+ "tells whether the economy is creating or destroying currency.",
				"currency", "direction");
		// Both "player" AND "uuid": the nickname for reading, the identifier for tracking. See PlayerRef.
		playerBalance = r.gauge("economy_player_balance",
				"Balance of an online player. Updated by event, so accurate to the second — "
						+ "no database reads.",
				"player", "uuid", "currency");
		series = new PlayerSeries(r, config);
	}

	@Override
	public void collect(MetricRegistry r) {
		var online = Bukkit.getOnlinePlayers().stream()
				.map(p -> PlayerRef.of(p.getUniqueId(), p.getName()))
				.toList();
		for (PlayerRef ref : series.select(online, playerBalance)) {
			Map<String, Double> m = balances.get(ref.uuid());
			if (m != null) {
				m.forEach((currency, value) -> playerBalance.set(value, ref.labels(currency)));
			}
		}
	}

	/**
	 * Wires up the listener, or stays quiet.
	 *
	 * <p>{@code registerEvent} with an {@code EventExecutor} is the form Bukkit exposes exactly
	 * for this case: the {@code @EventHandler} annotation requires a type known at compile time,
	 * this one isn't.
	 *
	 * @return true if the listener is in place.
	 */
	@SuppressWarnings("unchecked")
	public boolean attach(Plugin plugin) {
		try {
			Class<?> eventClass = Class.forName(EVENT_CLASS, false, plugin.getClass().getClassLoader());
			getPlayerMethod = eventClass.getMethod("getPlayer");
			getCurrencyMethod = eventClass.getMethod("getCurrency");
			getOldAmountMethod = eventClass.getMethod("getOldAmount");
			getNewAmountMethod = eventClass.getMethod("getNewAmount");
			getCurrencyIdMethod = getCurrencyMethod.getReturnType().getMethod("getId");

			Bukkit.getPluginManager().registerEvent(
					(Class<? extends Event>) eventClass, this, EventPriority.MONITOR,
					(listener, e) -> handle(e),
					plugin,
					// ignoreCancelled: ChangeBalanceEvent is cancellable, and a transaction
					// refused by another plugin must not be counted — otherwise we'd be
					// measuring intent instead of fact.
					true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			platform.warn("ExcellentEconomy: unexpected API, the money flow will not be "
					+ "measured — " + e);
			return false;
		}
	}

	private void handle(Event e) {
		try {
			Object player = getPlayerMethod.invoke(e);
			Object currency = getCurrencyMethod.invoke(e);
			if (!(player instanceof Player p) || currency == null) {
				return;
			}
			String id = String.valueOf(getCurrencyIdMethod.invoke(currency)).toLowerCase(Locale.ROOT);
			double oldAmount = ((Number) getOldAmountMethod.invoke(e)).doubleValue();
			double newAmount = ((Number) getNewAmountMethod.invoke(e)).doubleValue();
			double delta = newAmount - oldAmount;

			transactions.inc(id, delta >= 0 ? "deposit" : "withdraw");
			flow.add(Math.abs(delta), id, delta >= 0 ? "in" : "out");
			// Indexed by identifier, as everywhere else: a rename mid-session must not split
			// a player's balance across two keys.
			balances.computeIfAbsent(p.getUniqueId().toString(), k -> new ConcurrentHashMap<>())
					.put(id, newAmount);
		} catch (ReflectiveOperationException | RuntimeException error) {
			platform.warn("ExcellentEconomy: unreadable event — " + error);
		}
	}

	/** See the betonquest module: the cap bounds what's published, this bounds what's retained. */
	public void forget(String player) {
		balances.remove(player);
	}
}
