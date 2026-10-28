package com.lele.ledger;

/**
 * 收支用途（分类）词表。
 *
 * 用固定枚举，不让用户自建：饼图要按用途聚合成一个圆，
 * 分类一旦允许自由输入，很快就会出现「吃饭 / 餐饮 / 午饭」三个本该是一类的桶，
 * 占比图就失去意义了。
 */
public final class Category {

    /** 还没选用途时的占位名。饼图里单独一档，提醒用户去补。 */
    public static final String NONE = "未分类";

    public static final String OTHER = "其他";

    public static final String[] EXPENSE = {
            "餐饮", "交通", "购物", "服饰", "日用", "居住",
            "通讯", "娱乐", "医疗", "学习", "人情", "其他"
    };

    public static final String[] INCOME = {
            "工资", "奖金", "兼职", "退款", "红包", "理财", "报销", "其他"
    };

    /**
     * 每个用途一个固定颜色。饼图扇区和列表徽标都靠它，
     * 所以必须「名字 → 颜色」稳定，不能按当前出现的顺序临时分配——
     * 否则一笔新的餐饮进来，整个饼图的颜色就全变了。
     */
    private static final int[] PALETTE = {
            0xFF59653C, 0xFF8F9D78, 0xFFAE9871, 0xFF9B7258,
            0xFF6E8270, 0xFFB8AE91, 0xFF788E91, 0xFFA58A85,
            0xFF84996B, 0xFF7E8570, 0xFFC3AA78, 0xFF9C9F8B,
            0xFF748861, 0xFFAB9167, 0xFF6B7D77, 0xFFB4A287
    };

    private Category() {
    }

    /** 支出用支出词表，收入用收入词表。 */
    public static String[] forAmount(double amount) {
        return amount >= 0 ? INCOME : EXPENSE;
    }

    public static int colorOf(String name) {
        int idx = indexOf(name);
        if (idx < 0) {
            // 「未分类」和任何没见过的名字走同一个灰
            return 0xFF94A3B8;
        }
        return PALETTE[idx % PALETTE.length];
    }

    private static int indexOf(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < EXPENSE.length; i++) {
            if (EXPENSE[i].equals(name)) {
                return i;
            }
        }
        for (int i = 0; i < INCOME.length; i++) {
            if (INCOME[i].equals(name)) {
                return EXPENSE.length + i;
            }
        }
        return -1;
    }
}
