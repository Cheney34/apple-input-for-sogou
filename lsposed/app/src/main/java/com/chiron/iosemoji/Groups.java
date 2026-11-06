package com.chiron.iosemoji;

/**
 * 搜狗输入法表情面板的 10 个分组。
 *
 * <p>编号与 emojipackage/ 下的目录前缀一致，名字取自各分组 info.xml 的
 * chineseName，数量为该分组 emoji/ 下的图片张数。
 */
public final class Groups {

    public static final class Group {
        public final int id;
        public final String key;
        public final String label;
        public final int count;
        public final int icon;

        Group(int id, String key, String label, int count, int icon) {
            this.id = id;
            this.key = key;
            this.label = label;
            this.count = count;
            this.icon = icon;
        }
    }

    public static final Group[] ALL = {
            new Group(3, "smileys_emotion", "笑脸", 169, R.drawable.g_smileys_emotion),
            new Group(4, "people_body", "人物", 2295, R.drawable.g_people_body),
            new Group(5, "component", "组件", 5, R.drawable.g_component),
            new Group(6, "animals_nature", "动物与自然", 159, R.drawable.g_animals_nature),
            new Group(7, "food_drink", "食物与饮品", 131, R.drawable.g_food_drink),
            new Group(8, "travel_places", "旅行与地点", 218, R.drawable.g_travel_places),
            new Group(9, "activities", "活动", 85, R.drawable.g_activities),
            new Group(10, "objects", "物品", 264, R.drawable.g_objects),
            new Group(11, "symbols", "符号", 221, R.drawable.g_symbols),
            new Group(12, "flags", "旗帜", 270, R.drawable.g_flags),
    };

    /** 黄脸 / 符号 / 旗帜：日常最常用的三组。 */
    public static final int[] CORE = {3, 11, 12};

    private Groups() {
    }
}
