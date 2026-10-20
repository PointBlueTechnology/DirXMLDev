package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import com.pointblue.dirxml.dev.model.EnvironmentList;

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

    /**
     * Removes from {@code ds}, in place, the driver-set objects and drivers' jobs a deploy to {@code env} doesn't get
     * (role-based entitlement policies, notification templates, jobs); returns their tree paths
     * ({@code rbe-policies/<n>}, {@code templates/<n>}, {@code jobs/<n>}, {@code drivers/<d>/jobs/<n>}).
     */
    public static List<String> filterObjects(DriverSet ds, String env) {
        List<String> out = new ArrayList<>();
        if (env == null) {
            return out;
        }
        drop(ds.rbePolicies, p -> p.environments, p -> "rbe-policies/" + p.name, env, out);
        drop(ds.templates, t -> t.environments, t -> "templates/" + t.name, env, out);
        drop(ds.jobs, j -> j.environments, j -> "jobs/" + j.name, env, out);
        for (Driver d : ds.drivers) {
            drop(d.jobs, j -> j.environments, j -> "drivers/" + d.name + "/jobs/" + j.name, env, out);
        }
        return out;
    }

    private static <T> void drop(List<T> list, Function<T, List<String>> envs, Function<T, String> path, String env, List<String> out) {
        for (T o : new ArrayList<>(list)) {
            if (!EnvironmentList.includes(envs.apply(o), env)) {
                list.remove(o);
                out.add(path.apply(o));
            }
        }
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
                theirs.planned.clear();
                theirs.planned.addAll(mine.planned);
                if (mine.dn != null) {
                    theirs.dn = mine.dn;   // the DN the tree recorded: another environment's set may sit elsewhere
                }
                keep(theirs.jobs, mine.jobs, j -> j.name, j -> j.environments, env, markNew, "'" + mine.name + "' job", notes);
            }
        }
        if (names == null) {
            keep(live.rbePolicies, base.rbePolicies, p -> p.name, p -> p.environments, env, markNew, "entitlement policy", notes);
            keep(live.templates, base.templates, t -> t.name, t -> t.environments, env, markNew, "template", notes);
            keep(live.jobs, base.jobs, j -> j.name, j -> j.environments, env, markNew, "job", notes);
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

    /**
     * One list of named objects read from {@code env}'s vault, against the tree's: those the tree doesn't deploy to
     * {@code env} stay as the tree has them, the others keep their lists, and (with {@code markNew}) one only the vault
     * has is marked as {@code env}'s. A run of new ones is summed up in one note.
     */
    private static <T> void keep(List<T> live, List<T> base, Function<T, String> name, Function<T, List<String>> envs,
                                 String env, boolean markNew, String what, List<String> notes) {
        for (T mine : base) {
            T theirs = find(live, name, name.apply(mine));
            if (theirs != null) {
                planned(theirs).clear();
                planned(theirs).addAll(planned(mine));
            }
            if (!EnvironmentList.includes(envs.apply(mine), env)) {
                if (theirs != null) {
                    live.remove(theirs);
                }
                live.add(mine);
            } else if (theirs != null) {
                envs.apply(theirs).clear();
                envs.apply(theirs).addAll(envs.apply(mine));
            }
        }
        if (markNew) {
            int marked = 0;
            for (T o : live) {
                if (find(base, name, name.apply(o)) == null && envs.apply(o).isEmpty()) {
                    envs.apply(o).add(env);
                    marked++;
                }
            }
            if (marked > 0) {
                notes.add(marked + " " + what + (marked == 1 ? "" : "s") + " new to the tree: marked as " + env + "'s only");
            }
        }
    }

    /** The object's planned list (drivers' jobs, the set's jobs, policies, templates). */
    static List<String> planned(Object o) {
        if (o instanceof com.pointblue.dirxml.dev.model.Job j) {
            return j.planned;
        }
        if (o instanceof com.pointblue.dirxml.dev.model.EntitlementPolicy p) {
            return p.planned;
        }
        if (o instanceof com.pointblue.dirxml.dev.model.NotificationTemplate t) {
            return t.planned;
        }
        if (o instanceof Driver d) {
            return d.planned;
        }
        return new ArrayList<>();
    }

    private static <T> T find(List<T> list, Function<T, String> name, String n) {
        for (T o : list) {
            if (name.apply(o).equals(n)) {
                return o;
            }
        }
        return null;
    }
}
