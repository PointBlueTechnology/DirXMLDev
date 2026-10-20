package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Drivers that only some environments get ({@code driver.xml}'s {@code environments}, empty = every
 * environment): a driver not yet in production, or one that never will be, such as a test-only
 * loopback. One tree still serves every environment; what differs is which drivers each receives.
 *
 * <ul>
 *   <li>{@link #filter}: the tree as environment X gets it, without the drivers X doesn't. A diff
 *       then never reports them as added, and a deploy never creates them in X. Applied by
 *       {@link Overrides#apply}, so diff, plan, deploy, the known-state gate and simulate all agree.</li>
 *   <li>{@link #keepOnImport}: reading X's vault into the tree keeps the tree's drivers that X doesn't
 *       get (X not holding them is expected, not a removal), carries every driver's list over, and can
 *       mark a driver new to the tree as X's only, so it never reaches another environment unasked.</li>
 * </ul>
 */
public final class DriverEnvironments {

    private DriverEnvironments() {
    }

    /** Removes from {@code ds}, in place, the drivers a deploy to {@code env} doesn't get; returns their names. */
    public static List<String> filter(DriverSet ds, String env) {
        List<String> out = new ArrayList<>();
        if (env == null) {
            return out;
        }
        for (Driver d : new ArrayList<>(ds.drivers)) {
            if (!d.deploysTo(env)) {
                ds.drivers.remove(d);
                out.add(d.name);
            }
        }
        return out;
    }

    /**
     * {@code live} was read from {@code env}'s vault and is about to become the tree; {@code base} is the
     * tree on disk (null on a first import). In place, on {@code live}:
     * <ul>
     *   <li>a driver of the tree that {@code env} doesn't get stays exactly as the tree has it, whether or
     *       not the vault holds one of that name;</li>
     *   <li>every other driver of the tree keeps its list and the DN the tree recorded;</li>
     *   <li>with {@code markNew}, a driver only the vault has gets {@code environments = env}: on a tree that
     *       other environments share, it then reaches them only when someone adds them.</li>
     * </ul>
     * With {@code names}, only those drivers are looked at (an update of some drivers, the rest of
     * {@code live} being the tree already); null looks at all. Returns notes for the import's report.
     */
    public static List<String> keepOnImport(DriverSet live, String env, DriverSet base, boolean markNew, Collection<String> names) {
        List<String> notes = new ArrayList<>();
        if (base == null || env == null) {
            return notes;
        }
        for (Driver mine : base.drivers) {
            if (names != null && !names.contains(mine.name)) {
                continue;
            }
            Driver theirs = live.driver(mine.name);
            if (!mine.deploysTo(env)) {
                if (theirs != null) {
                    live.drivers.remove(theirs);
                    notes.add("'" + mine.name + "' is in " + env + "'s vault, but the tree doesn't deploy it to " + env
                        + " (environments=\"" + String.join(", ", mine.environments) + "\"): kept as the tree has it");
                }
                live.drivers.add(mine);
            } else if (theirs != null) {
                theirs.environments.clear();
                theirs.environments.addAll(mine.environments);
                if (mine.dn != null) {
                    theirs.dn = mine.dn;   // the DN the tree recorded: another environment's set may sit elsewhere
                }
            }
        }
        if (markNew) {
            for (Driver d : live.drivers) {
                if ((names == null || names.contains(d.name)) && base.driver(d.name) == null && d.environments.isEmpty()) {
                    d.environments.add(env);
                    notes.add("'" + d.name + "' is new to the tree: marked as " + env + "'s only (environments=\"" + env
                        + "\"); add other environments to deploy it there");
                }
            }
        }
        return notes;
    }
}
