package com.example.life_sanctuary;

/** 单株花期记录；failed 仅表示受粉失败，不关闭雄性供粉功能。 */
public record PlantFlowerRound(long id, boolean active, boolean failed) {
    public PlantFlowerRound {
        if (id < 0) throw new IllegalArgumentException("Negative flower round id");
    }
    public boolean allowsPollenSupply() { return active; }
    public boolean allowsPollenReception() { return active && !failed; }
}
