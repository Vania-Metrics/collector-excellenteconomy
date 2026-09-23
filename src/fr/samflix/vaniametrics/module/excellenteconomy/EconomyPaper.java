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
 * Métriques de l'économie ExcellentEconomy.
 *
 * <p>C'est ici qu'est l'écoute de la déconnexion, et non dans le collecteur : celui-ci branche son
 * propre écouteur par réflexion, sur un seul type d'événement. Mélanger les deux mécaniques dans
 * une même classe rendrait le code difficile à suivre pour rien.
 */
public final class EconomyPaper extends JavaPlugin implements Listener {

	private EconomyCollector collecteur;

	@Override
	public void onEnable() {
		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new EconomyCollector(metriques.plateforme(), metriques.config());
		if (!collecteur.brancher(this)) {
			// L'avertissement est déjà posé par le collecteur. On n'enregistre rien : des
			// compteurs déclarés et jamais alimentés se liraient comme une économie morte.
			return;
		}
		metriques.enregistrer(collecteur);
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		if (collecteur != null) {
			collecteur.oublier(e.getPlayer().getUniqueId().toString());
		}
	}
}
