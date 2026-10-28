package com.lele.ledger;

/** Shared category symbols for list rows and category pickers. */
final class LedgerIcons {
    private LedgerIcons() { }

    static int forCategory(String category) {
        if (category == null) return R.drawable.ic_ledger;
        switch (category) {
            case "餐饮": return R.drawable.ic_food;
            case "交通": return R.drawable.ic_transport;
            case "购物": case "服饰": case "日用": return R.drawable.ic_shopping;
            case "居住": return R.drawable.ic_home;
            case "人情": case "红包": case "奖金": return R.drawable.ic_gift;
            case "学习": return R.drawable.ic_book;
            case "工资": case "兼职": case "报销": return R.drawable.ic_briefcase;
            case "理财": return R.drawable.ic_chart;
            default: return R.drawable.ic_ledger;
        }
    }
}
