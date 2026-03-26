module com.botwithus.bot.scripts.woodcutting {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires imgui.binding;
    requires com.google.gson;

    provides com.botwithus.bot.api.BotScript
        with com.botwithus.bot.scripts.woodcutting.WoodcuttingScript;
}
