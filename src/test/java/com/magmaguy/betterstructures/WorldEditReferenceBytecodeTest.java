package com.magmaguy.betterstructures;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldEdit / FAWE への参照が {@code integration/worldedit/} の外へ広がらないことを、
 * コンパイル済みクラスファイルをバイト列として読んで確かめる（リフレクションは使わない）。
 * <p>
 * 今は多数のクラスが参照しているので、現状の違反クラスを許可リスト
 * （{@code worldedit-reference-allowlist.txt}）に固定し、リスト外の新しい違反だけを失敗にする。
 * 許可リストは「参照してもよい」であり「参照していなければならない」ではない。
 * 段C で本体がこのリストを空にする。
 */
class WorldEditReferenceBytecodeTest {
    private static final String ALLOWED_PACKAGE = "com/magmaguy/betterstructures/integration/worldedit/";
    /** WorldGuard の API 自体が WorldEdit の型（BlockVector3 等）を引数に取るため、WorldGuard 連携も許す。 */
    private static final String ALLOWED_WORLDGUARD_PACKAGE = "com/magmaguy/betterstructures/integration/worldguard/";
    private static final List<String> FORBIDDEN_PREFIXES = List.of("com/sk89q/", "com/fastasyncworldedit/");

    @Test
    void noNewClassOutsideTheIntegrationPackageReferencesWorldEdit() throws IOException {
        Set<String> allowed = loadAllowlist();
        Path classesRoot = Path.of("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classesRoot), "コンパイル済みクラスが見つからない: " + classesRoot.toAbsolutePath());

        Set<String> violations = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classesRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
                String internalName = classesRoot.relativize(file).toString().replace('\\', '/');
                internalName = internalName.substring(0, internalName.length() - ".class".length());
                if (internalName.startsWith(ALLOWED_PACKAGE) || internalName.startsWith(ALLOWED_WORLDGUARD_PACKAGE)) continue;
                if (referencesWorldEdit(Files.readAllBytes(file))) violations.add(internalName);
            }
        }

        violations.removeAll(allowed);
        assertTrue(violations.isEmpty(),
                "integration/worldedit/ の外に WorldEdit/FAWE への新しい参照がある（許可リストにも無い）: " + violations);
    }

    @Test
    void allowlistListsOnlyClassesOutsideTheIntegrationPackage() throws IOException {
        for (String name : loadAllowlist()) {
            assertFalse(name.startsWith(ALLOWED_PACKAGE), "許可リストに integration/worldedit/ のクラスは不要: " + name);
        }
    }

    private static boolean referencesWorldEdit(byte[] classBytes) {
        boolean[] found = {false};
        Remapper recorder = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName != null) {
                    for (String prefix : FORBIDDEN_PREFIXES) {
                        if (internalName.startsWith(prefix)) found[0] = true;
                    }
                }
                return internalName;
            }
        };
        // 型参照（継承・フィールド・メソッド記述子・命令・アノテーション）は全て Remapper.map を通る。
        new ClassReader(classBytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) { }, recorder), 0);
        return found[0];
    }

    private static Set<String> loadAllowlist() throws IOException {
        try (InputStream in = WorldEditReferenceBytecodeTest.class.getResourceAsStream("/worldedit-reference-allowlist.txt")) {
            assertNotNull(in, "許可リストのリソースが見つからない");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
