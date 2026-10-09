// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact private record contracts plus unchanged values from real branch snapshots. */
public final class OwnershipContractProbe {
    private static int checks;
    private OwnershipContractProbe() {}
    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("ownership value contract");
    }
    private static Object value(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) throws Exception {
        Constructor<?> result = type.getDeclaredConstructor(parameters); result.setAccessible(true); return result;
    }
    private static void rejectsNull(Constructor<?> constructor, Object[] arguments) throws Exception {
        try { constructor.newInstance(arguments); throw new AssertionError("missing null rejection"); }
        catch (InvocationTargetException failure) { require(failure.getCause() instanceof NullPointerException); }
    }
    public static void main(String[] args) throws Exception {
        Class<?> nodeType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$AllocationInfo");
        Class<?> stateType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$AllocationState");
        Class<?> valueType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$AllocationStateSnapshot");
        Class<?> slotType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$ArraySlot");
        Class<?> snapshotType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$OwnershipSnapshot");
        Constructor<?> nodeConstructor = constructor(nodeType, int.class);
        Object node = nodeConstructor.newInstance(0), alias = node, different = nodeConstructor.newInstance(0);
        Constructor<?> stateConstructor = constructor(valueType, stateType, String.class, boolean.class);
        Object active = stateType.getEnumConstants()[0];
        Object firstState = stateConstructor.newInstance(active, new String("reason"), false);
        Object equalState = stateConstructor.newInstance(active, new String("reason"), false);
        require(firstState != equalState && firstState.equals(equalState) && firstState.hashCode() == equalState.hashCode());
        require(!firstState.equals(stateConstructor.newInstance(active, "different", false)));
        require(!firstState.equals(stateConstructor.newInstance(active, "reason", true)));
        Constructor<?> slotConstructor = constructor(slotType, nodeType, int.class);
        Object slot = slotConstructor.newInstance(node, 65_537), equalSlot = slotConstructor.newInstance(alias, 65_537);
        require(slot != equalSlot && slot.equals(equalSlot) && slot.hashCode() == equalSlot.hashCode());
        require(!slot.equals(slotConstructor.newInstance(different, 65_537)));
        require(!slot.equals(slotConstructor.newInstance(node, 0)));
        Map<Object, Object> states = new IdentityHashMap<>(); states.put(node, firstState);
        Map<Object, Object> slots = new LinkedHashMap<>(); slots.put(slot, node);
        Map<Object, Object> fields = new LinkedHashMap<>(); fields.put(new String("field"), node);
        Map<Object, Object> borrows = new IdentityHashMap<>(); Set<Object> child = Set.of(node); borrows.put(different, child);
        Map<Object, Object> pools = new IdentityHashMap<>(); pools.put(node, different);
        Set<Object> exposed = new LinkedHashSet<>(); exposed.add(node);
        Set<Object> live = new LinkedHashSet<>(); live.add(node);
        Object[] arguments = {states, slots, fields, borrows, pools, exposed, live};
        Constructor<?> snapshotConstructor = constructor(snapshotType, Map.class, Map.class, Map.class, Map.class, Map.class, Set.class, Set.class);
        Object saved = snapshotConstructor.newInstance(arguments);
        require(((Map<?, ?>)value(saved, "states")).get(alias) == firstState);
        require(((Map<?, ?>)value(saved, "states")).get(different) == null);
        require(((Map<?, ?>)value(saved, "knownArraySlots")).get(equalSlot) == node);
        require(((Map<?, ?>)value(saved, "borrowedOwnedFields")).get(new String("field")) == node);
        require(((Map<?, ?>)value(saved, "retainedBorrows")).get(different) == child);
        String[] names = {"states", "knownArraySlots", "borrowedOwnedFields", "retainedBorrows", "poolOwners", "exposedContainerContents", "unfreedLive"};
        for (int index = 0; index < names.length; index++) {
            Object[] invalid = arguments.clone(); invalid[index] = null; rejectsNull(snapshotConstructor, invalid);
            if (index < 5) {
                Map<Object, Object> nullKey = new LinkedHashMap<>(); nullKey.put(null, node);
                invalid[index] = nullKey; rejectsNull(snapshotConstructor, invalid);
                Map<Object, Object> nullValue = new LinkedHashMap<>(); nullValue.put(node, null);
                invalid[index] = nullValue; rejectsNull(snapshotConstructor, invalid);
                ((Map<?, ?>)arguments[index]).clear();
                Map<?, ?> copy = (Map<?, ?>)value(saved, names[index]); require(copy.size() == 1);
                try { copy.clear(); throw new AssertionError("mutable snapshot map"); }
                catch (UnsupportedOperationException expected) { require(copy.size() == 1); }
            } else {
                Set<Object> nullMember = new LinkedHashSet<>(); nullMember.add(null);
                invalid[index] = nullMember; rejectsNull(snapshotConstructor, invalid);
                ((Set<?>)arguments[index]).clear(); Set<?> copy = (Set<?>)value(saved, names[index]); require(copy.size() == 1);
                try { copy.clear(); throw new AssertionError("mutable snapshot set"); }
                catch (UnsupportedOperationException expected) { require(copy.size() == 1); }
            }
        }
        OwnershipWork work = new OwnershipWork(8, 1, true);
        work.run(); work.verify();
        var versionsField = OwnershipWork.class.getDeclaredField("versions"); versionsField.setAccessible(true);
        var nodesField = OwnershipWork.class.getDeclaredField("nodes"); nodesField.setAccessible(true);
        List<?> versions = (List<?>)versionsField.get(work);
        Object[] nodes = (Object[])nodesField.get(work);
        Object unchangedBefore = ((Map<?, ?>)value(versions.get(0), "states")).get(nodes[2]);
        Object unchangedAfter = ((Map<?, ?>)value(versions.get(1), "states")).get(nodes[2]);
        require(unchangedBefore != unchangedAfter && unchangedBefore.equals(unchangedAfter));
        require(unchangedBefore.hashCode() == unchangedAfter.hashCode());
        require(!((Map<?, ?>)value(versions.get(0), "states")).get(nodes[0]).equals(
                ((Map<?, ?>)value(versions.get(1), "states")).get(nodes[0])));
        System.out.println("PASS: " + checks + " original ownership snapshot value checks");
    }
}
