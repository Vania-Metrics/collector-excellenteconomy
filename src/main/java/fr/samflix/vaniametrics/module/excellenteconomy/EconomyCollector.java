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
import fr.samflix.vaniametrics.api.Joueur;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * L'économie multi-monnaie d'ExcellentEconomy — par les événements, et par RÉFLEXION.
 *
 * <p>COMPLÉMENTAIRE DU MODULE {@code essentials}, qui donne le TOTAL en circulation en lisant le
 * cache déjà calculé d'EssentialsX. Celui-ci donne le MOUVEMENT : qui gagne, qui dépense, combien,
 * dans quelle monnaie. Un total qui ne bouge pas et un flux intense ne décrivent pas la même
 * économie, et aucune des deux métriques ne remplace l'autre.
 *
 * <p>POURQUOI DE LA RÉFLEXION ICI, ET NULLE PART AILLEURS. ExcellentEconomy est compilé en
 * <b>classe 69, c'est-à-dire Java 25</b> — vérifié dans son jar. Un javac qui vise Java 21 refuse
 * de LIRE un tel fichier, même pour une simple signature :
 *
 * <pre>
 * bad class file: ExcellentEconomy-2.8.0.jar(…/ChangeBalanceEvent.class)
 *   class file has wrong version 69.0, should be 65.0
 * </pre>
 *
 * <p>Les deux sorties propres étaient de compiler ce module en {@code --release 25}, ce qui impose
 * un JDK 25 pour construire tout le dépôt, ou de s'en passer. La réflexion est le troisième
 * chemin : le module reste compilable sur le JDK 21 du dépôt, et {@code registerEvent} accepte une
 * classe obtenue à l'exécution. Le prix est qu'un changement de signature chez NightExpress ne se
 * verra pas à la compilation — il se verra au démarrage, dans un avertissement, et le module se
 * taira au lieu de tomber.
 */
public final class EconomyCollector implements Collector, Listener {

	private static final String CLASSE_EVENEMENT =
			"su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent";

	private final Platform plateforme;
	private final Config config;

	private Counter transactions;
	private Counter flux;
	private Gauge soldeJoueur;
	private PlayerSeries series;

	/** Le dernier solde connu de chaque joueur connecté, tenu à jour par les événements. */
	private final Map<String, Map<String, Double>> soldes = new ConcurrentHashMap<>();

	private Method lireJoueur;
	private Method lireMonnaie;
	private Method lireAncien;
	private Method lireNouveau;
	private Method lireIdMonnaie;

	public EconomyCollector(Platform plateforme, Config config) {
		this.plateforme = plateforme;
		this.config = config;
	}

	@Override
	public String nom() {
		return "economy";
	}

	@Override
	public String origine() {
		return "ExcellentEconomy";
	}

	@Override
	public void declarer(MetricRegistry r) {
		transactions = r.counter("economy_transactions_total",
				"Mouvements de compte. type = deposit|withdraw.", "currency", "type");
		flux = r.counter("economy_flow_total",
				"Montants déplacés, en valeur absolue. direction = in|out. Le rapport des deux "
						+ "dit si l'économie crée ou détruit de la monnaie.",
				"currency", "direction");
		// « player » ET « uuid » : le pseudonyme pour lire, l'identifiant pour suivre. Voir Joueur.
		soldeJoueur = r.gauge("economy_player_balance",
				"Solde d'un joueur connecté. Mis à jour par événement, donc juste à la seconde — "
						+ "aucune lecture de base.",
				"player", "uuid", "currency");
		series = new PlayerSeries(r, config);
	}

	@Override
	public void relever(MetricRegistry r) {
		var connectes = Bukkit.getOnlinePlayers().stream()
				.map(j -> Joueur.de(j.getUniqueId(), j.getName()))
				.toList();
		for (Joueur qui : series.retenir(connectes, soldeJoueur)) {
			Map<String, Double> m = soldes.get(qui.uuid());
			if (m != null) {
				m.forEach((monnaie, valeur) -> soldeJoueur.set(valeur, qui.etiquettes(monnaie)));
			}
		}
	}

	/**
	 * Branche l'écoute, ou se tait proprement.
	 *
	 * <p>{@code registerEvent} avec un {@code EventExecutor} est la forme que Bukkit expose
	 * justement pour ce cas : l'annotation {@code @EventHandler} exige un type connu à la
	 * compilation, celle-ci non.
	 *
	 * @return vrai si l'écoute est en place.
	 */
	@SuppressWarnings("unchecked")
	public boolean brancher(Plugin plugin) {
		try {
			Class<?> evenement = Class.forName(CLASSE_EVENEMENT, false, plugin.getClass().getClassLoader());
			lireJoueur = evenement.getMethod("getPlayer");
			lireMonnaie = evenement.getMethod("getCurrency");
			lireAncien = evenement.getMethod("getOldAmount");
			lireNouveau = evenement.getMethod("getNewAmount");
			lireIdMonnaie = lireMonnaie.getReturnType().getMethod("getId");

			Bukkit.getPluginManager().registerEvent(
					(Class<? extends Event>) evenement, this, EventPriority.MONITOR,
					(ecouteur, e) -> traiter(e),
					plugin,
					// ignoreCancelled : ChangeBalanceEvent est annulable, et une transaction
					// refusée par un autre plugin ne doit pas être comptée — sinon on mesurerait
					// les intentions et non les faits.
					true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			plateforme.avertir("ExcellentEconomy : API inattendue, le flux monétaire ne sera pas "
					+ "mesuré — " + e);
			return false;
		}
	}

	private void traiter(Event e) {
		try {
			Object joueur = lireJoueur.invoke(e);
			Object monnaie = lireMonnaie.invoke(e);
			if (!(joueur instanceof Player p) || monnaie == null) {
				return;
			}
			String id = String.valueOf(lireIdMonnaie.invoke(monnaie)).toLowerCase(Locale.ROOT);
			double ancien = ((Number) lireAncien.invoke(e)).doubleValue();
			double nouveau = ((Number) lireNouveau.invoke(e)).doubleValue();
			double delta = nouveau - ancien;

			transactions.inc(id, delta >= 0 ? "deposit" : "withdraw");
			flux.add(Math.abs(delta), id, delta >= 0 ? "in" : "out");
			// Indexé par identifiant, comme partout : un renommage en cours de session ne doit
			// pas scinder le solde d'un joueur sur deux clés.
			soldes.computeIfAbsent(p.getUniqueId().toString(), k -> new ConcurrentHashMap<>())
					.put(id, nouveau);
		} catch (ReflectiveOperationException | RuntimeException erreur) {
			plateforme.avertir("ExcellentEconomy : événement illisible — " + erreur);
		}
	}

	/** Voir le module betonquest : le plafond borne ce qui est publié, ceci ce qui est retenu. */
	public void oublier(String joueur) {
		soldes.remove(joueur);
	}
}
