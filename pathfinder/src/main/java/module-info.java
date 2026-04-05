module com.botwithus.bot.pathfinder {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires com.google.gson;
    requires imgui.binding;

    opens com.botwithus.bot.pathfinder to com.google.gson;

    exports com.botwithus.bot.pathfinder;

    provides com.botwithus.bot.api.BotScript
        with com.botwithus.bot.pathfinder.PathfinderScript;
}
