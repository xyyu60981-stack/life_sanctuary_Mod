package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.RandomSource;

/** Population-plan data, not a flower sex or a new per-tick search. */
public final class CycadNaturalSexes {
    private CycadNaturalSexes() {}
    public enum Sex { FEMALE, MALE }
    /** Preserve mixed selections; if all match, flip one random member as required by the cycad baseline. */
    public static List<Sex> ensureBoth(List<Sex> selected, RandomSource random) {
        Objects.requireNonNull(random);
        var result = new ArrayList<>(List.copyOf(selected));
        if (result.size() < 2) throw new IllegalArgumentException("Both-sex population needs at least two members");
        var first = result.get(0);
        if (result.stream().allMatch(first::equals)) result.set(random.nextInt(result.size()), first == Sex.FEMALE ? Sex.MALE : Sex.FEMALE);
        return List.copyOf(result);
    }
    public static List<Sex> sampleTansachana(int members, RandomSource random) {
        if (members < TansachanaCycad.PROFILE.minimumNaturalMembers() || members > TansachanaCycad.PROFILE.maximumNaturalMembers())
            throw new IllegalArgumentException("Tansachana population must contain two or three non-offshoot members");
        var result = new ArrayList<Sex>();
        for (int i = 0; i < members; i++) result.add(random.nextBoolean() ? Sex.FEMALE : Sex.MALE);
        return ensureBoth(result, random);
    }
}
