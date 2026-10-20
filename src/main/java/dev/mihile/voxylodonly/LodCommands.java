package dev.mihile.voxylodonly;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

/** /voxylod and /voxylodonly share one implementation so both names behave identically. */
final class LodCommands {
    private static final String NOT_READY = "VoxyServer 가 아직 준비되지 않았습니다 (서버 시작 직후이거나 전용 서버가 아님)";

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<LodOnlyManager> manager) {
        dispatcher.register(build("voxylod", manager));
        dispatcher.register(build("voxylodonly", manager));
    }

    private static void ok(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
    }
    private static void info(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.YELLOW), false);
    }
    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text)); return 0;
    }
    private static String autoNote(LodOnlyManager m) {
        return m.automaticEnabled() ? "자동 생성: 켜짐" : "자동 생성: 꺼짐 (/voxylod automatic on 으로 켜기)";
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name, Supplier<LodOnlyManager> manager) {
        var root = Commands.literal(name).requires(source -> source.hasPermission(2));

        root.then(Commands.literal("status")
                .executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    ctx.getSource().sendSuccess(() -> Component.literal(m.summary()), false); return 1;
                })
                .then(Commands.literal("detail").executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    ctx.getSource().sendSuccess(() -> Component.literal(m.status()), false); return 1;
                })));

        root.then(Commands.literal("reload").executes(ctx -> {
            var m = manager.get();
            if (m == null) return fail(ctx.getSource(), NOT_READY);
            try {
                m.reload(LodOnlyConfig.load());
                ok(ctx.getSource(), "설정 파일을 다시 읽었습니다. 진행 중인 작업이 정리되면 적용됩니다.\n"
                        + " 명령으로 바꾼 자동 생성 켜짐/꺼짐은 서버 재시작 전까지 유지됩니다. (" + autoNote(m) + ")");
                return 1;
            } catch (RuntimeException e) {
                return fail(ctx.getSource(), "설정 reload 실패 - 기존 설정 유지: " + e.getMessage());
            }
        }));

        root.then(Commands.literal("radius")
                .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 128)).executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    int radius = IntegerArgumentType.getInteger(ctx, "chunks");
                    int before = m.radiusChunks();
                    try {
                        LodOnlyConfig.saveRadius(radius); m.radius(radius);
                    } catch (java.io.IOException | RuntimeException e) {
                        return fail(ctx.getSource(), "반경 변경 실패 - 기존 설정 유지: " + e.getMessage());
                    }
                    String change = before == radius ? "이미 " + radius + "청크입니다 (설정 파일에 저장됨)"
                            : "자동 생성 반경: " + before + " → " + radius + "청크 (설정 파일에 저장됨)";
                    if (m.automaticEnabled()) ok(ctx.getSource(), change + "\n 새 반경으로 바로 이어서 생성합니다.");
                    else info(ctx.getSource(), change + "\n 현재 자동 생성이 꺼져 있어(stop 상태) 아직 생성하지 않습니다. "
                            + "/voxylod automatic on 을 하면 이 반경으로 시작합니다.");
                    return 1;
                })));

        root.then(Commands.literal("pause").executes(ctx -> {
            var m = manager.get();
            if (m == null) return fail(ctx.getSource(), NOT_READY);
            if (!m.pause()) return fail(ctx.getSource(), "일시정지할 진행 중인 LOD 작업이 없습니다 (" + autoNote(m) + ")");
            info(ctx.getSource(), "LOD 작업을 일시정지했습니다. 진행 상황은 유지되며 /voxylod resume 으로 재개합니다.");
            return 1;
        }));

        root.then(Commands.literal("resume").executes(ctx -> {
            var m = manager.get();
            if (m == null) return fail(ctx.getSource(), NOT_READY);
            if (!m.resume()) return fail(ctx.getSource(), "수동으로 일시정지된 작업이 없습니다. 자동 생성이 꺼져 있다면 /voxylod automatic on 을 사용하세요. (" + autoNote(m) + ")");
            ok(ctx.getSource(), "LOD 작업을 재개했습니다. (서버 부하가 높으면 자동으로 양보합니다)");
            return 1;
        }));

        root.then(Commands.literal("stop").executes(ctx -> {
            var m = manager.get();
            if (m == null) return fail(ctx.getSource(), NOT_READY);
            boolean was = m.automaticEnabled();
            m.stopWork();
            info(ctx.getSource(), (was ? "LOD 생성을 정지했습니다." : "이미 정지 상태입니다. 진행 중이던 작업만 정리했습니다.")
                    + "\n 이미 만든 LOD는 유지되고, 서버를 재시작하기 전까지 정지 상태로 남습니다."
                    + "\n 다시 켜기: /voxylod automatic on  (반경 설정: /voxylod radius <청크>)");
            return 1;
        }));

        root.then(Commands.literal("cancel").executes(ctx -> {
            var m = manager.get();
            if (m == null) return fail(ctx.getSource(), NOT_READY);
            m.cancel();
            info(ctx.getSource(), "현재 진행 중인 LOD 작업만 취소했습니다. (" + autoNote(m) + ")"
                    + (m.automaticEnabled() ? "\n 자동 생성이 켜져 있어 잠시 후 다시 필요한 곳부터 이어집니다. 완전히 멈추려면 /voxylod stop" : ""));
            return 1;
        }));

        root.then(Commands.literal("automatic")
                .then(Commands.literal("on").executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    boolean was = m.automaticEnabled();
                    m.automatic(true);
                    ok(ctx.getSource(), (was ? "자동 생성은 이미 켜져 있습니다." : "자동 생성을 켰습니다.")
                            + " 반경 " + m.radiusChunks() + "청크, 플레이어 주변부터 생성합니다.");
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    boolean was = m.automaticEnabled();
                    m.automatic(false);
                    info(ctx.getSource(), (was ? "자동 생성을 껐습니다." : "자동 생성은 이미 꺼져 있습니다.")
                            + " 서버를 재시작하기 전까지 유지됩니다. (반경 " + m.radiusChunks() + "청크 설정은 그대로)");
                    return 1;
                })));

        root.then(Commands.literal("generate")
                .then(Commands.argument("dimension", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .then(Commands.argument("chunkX", IntegerArgumentType.integer(-1_875_000, 1_875_000))
                .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-1_875_000, 1_875_000))
                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 128)).executes(ctx -> {
                    var m = manager.get();
                    if (m == null) return fail(ctx.getSource(), NOT_READY);
                    var dimension = net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "dimension");
                    for (var candidate : ctx.getSource().getServer().getAllLevels()) {
                        if (!candidate.dimension().location().equals(dimension)) continue;
                        int cx = IntegerArgumentType.getInteger(ctx, "chunkX"), cz = IntegerArgumentType.getInteger(ctx, "chunkZ");
                        int r = IntegerArgumentType.getInteger(ctx, "radiusChunks");
                        if (!m.generate(candidate, cx, cz, r))
                            return fail(ctx.getSource(), "다른 LOD 작업이 진행 중이거나 비활성 상태입니다. /voxylod status 확인 후 stop 또는 cancel 하세요.");
                        ok(ctx.getSource(), "LOD 생성 시작: " + dimension + " 청크(" + cx + ", " + cz + ") 반경 " + r + "청크");
                        return 1;
                    }
                    return fail(ctx.getSource(), "알 수 없는 차원: " + dimension);
                }))))));

        var z = Commands.argument("centerZ", IntegerArgumentType.integer(-29_999_999, 29_999_999));
        for (var shape : GenerationArea.Shape.values()) {
            z.then(Commands.literal(shape.name()).then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(0, GenerationArea.MAX_RADIUS))
                    .then(Commands.literal("start").executes(ctx -> {
                        var m = manager.get();
                        if (m == null) return fail(ctx.getSource(), NOT_READY);
                        var area = new GenerationArea(IntegerArgumentType.getInteger(ctx, "centerX"), IntegerArgumentType.getInteger(ctx, "centerZ"),
                                shape, IntegerArgumentType.getInteger(ctx, "radiusBlocks"));
                        if (!m.startArea(ctx.getSource().getLevel(), area))
                            return fail(ctx.getSource(), "다른 LOD 작업이 진행 중이거나 비활성 상태입니다. /voxylod status 확인 후 stop 하고 정리될 때까지 기다리세요.");
                        ok(ctx.getSource(), "지정 구역 LOD 생성을 접수했습니다: " + ctx.getSource().getLevel().dimension().location()
                                + ", " + area.description() + ", 후보 " + area.totalChunks() + "청크\n"
                                + " 이 구역이 끝날 때까지 자동 생성은 꺼집니다. 끝난 뒤 /voxylod automatic on 으로 다시 켤 수 있습니다.");
                        return 1;
                    }))));
        }
        root.then(Commands.argument("centerX", IntegerArgumentType.integer(-29_999_999, 29_999_999)).then(z));
        return root;
    }
}
