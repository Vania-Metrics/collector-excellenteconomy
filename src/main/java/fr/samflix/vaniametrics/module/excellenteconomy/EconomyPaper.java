package fr.samflix.vaniametrics.module.excellenteconomy;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * ExcellentEconomy metrics.
 *
 * <p>The quit listener lives here, not in the collector: the collector wires up its own
 * listener via reflection, for a single event type. Mixing both mechanisms in one class would
 * make the code harder to follow for no benefit.
 */
public final class EconomyPaper extends JavaPlugin implements Listener {

	private EconomyCollector collector;

	@Override
	public void onEnable() {
		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new EconomyCollector(metrics.platform(), metrics.config());
		if (!collector.attach(this)) {
			// The warning was already logged by the collector. Nothing is registered: gauges
			// declared but never fed would read like a dead economy.
			return;
		}
		metrics.register(collector);
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		if (collector != null) {
			collector.forget(e.getPlayer().getUniqueId().toString());
		}
	}
}
