package com.einent.veinmining.systems;

import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Calcula as arestas externas da uniao de um conjunto de blocos (celulas da grade). */
public final class OutlineBuilder {

    /** Aresta reta da grade: comeca no ponto de grade (x,y,z) e anda {@code length} unidades em +axis (0=X, 1=Y, 2=Z). */
    public record Edge(int axis, int x, int y, int z, int length) {}

    private record UnitEdge(int axis, int x, int y, int z) {}

    private static final int[][] PERP = {{1, 2}, {0, 2}, {0, 1}};

    private OutlineBuilder() {}

    public static List<Edge> build(Collection<Vector3i> cells) {
        if (cells.isEmpty()) return List.of();
        Set<Vector3i> occupied = new HashSet<>(cells);

        Set<UnitEdge> candidates = new HashSet<>();
        for (Vector3i c : occupied) {
            for (int a = 0; a < 3; a++) {
                int u = PERP[a][0];
                int v = PERP[a][1];
                for (int du = 0; du < 2; du++) {
                    for (int dv = 0; dv < 2; dv++) {
                        int[] p = {c.x, c.y, c.z};
                        p[u] += du;
                        p[v] += dv;
                        candidates.add(new UnitEdge(a, p[0], p[1], p[2]));
                    }
                }
            }
        }

        Set<UnitEdge> kept = new HashSet<>();
        for (UnitEdge e : candidates) {
            int[] p = {e.x, e.y, e.z};
            int a = e.axis;
            int u = PERP[a][0];
            int v = PERP[a][1];
            boolean q00 = has(occupied, p, a, u, v, -1, -1);
            boolean q10 = has(occupied, p, a, u, v, 0, -1);
            boolean q01 = has(occupied, p, a, u, v, -1, 0);
            boolean q11 = has(occupied, p, a, u, v, 0, 0);
            int n = (q00 ? 1 : 0) + (q10 ? 1 : 0) + (q01 ? 1 : 0) + (q11 ? 1 : 0);
            boolean keep = n == 1 || n == 3 || (n == 2 && ((q00 && q11) || (q10 && q01)));
            if (keep) kept.add(e);
        }
        return merge(kept);
    }

    /**
     * Contorno so das faces da selecao voltadas para um vizinho "aberto" (ar), ou seja, a superficie que o jogador
     * enxerga. Arestas entre duas faces visiveis coplanares sao descartadas; dobras entre planos sao mantidas.
     * Retorna lista vazia se nenhuma face e visivel.
     */
    public static List<Edge> buildVisible(Collection<Vector3i> cells, Predicate<Vector3i> open) {
        Set<Vector3i> occupied = new HashSet<>(cells);
        // face = celula + eixo da normal + sinal (0 = -, 1 = +)
        record Face(int x, int y, int z, int n, int s) {}
        Set<Face> faces = new HashSet<>();
        for (Vector3i c : occupied) {
            for (int n = 0; n < 3; n++) {
                for (int s = 0; s < 2; s++) {
                    int[] q = {c.x, c.y, c.z};
                    q[n] += s == 1 ? 1 : -1;
                    Vector3i nb = new Vector3i(q[0], q[1], q[2]);
                    if (!occupied.contains(nb) && open.test(nb)) faces.add(new Face(c.x, c.y, c.z, n, s));
                }
            }
        }
        Set<UnitEdge> kept = new HashSet<>();
        for (Face f : faces) {
            int n = f.n;
            for (int k = 0; k < 2; k++) {
                int a = PERP[n][k];        // eixo ao longo da aresta
                int b = PERP[n][1 - k];    // eixo perpendicular a aresta, dentro do plano
                for (int side = 0; side < 2; side++) {
                    int[] cell = {f.x, f.y, f.z};
                    int[] nbCell = {f.x, f.y, f.z};
                    nbCell[b] += side == 1 ? 1 : -1;
                    if (faces.contains(new Face(nbCell[0], nbCell[1], nbCell[2], n, f.s))) continue;
                    int[] p = {cell[0], cell[1], cell[2]};
                    p[n] += f.s;
                    p[b] += side;
                    kept.add(new UnitEdge(a, p[0], p[1], p[2]));
                }
            }
        }
        return merge(kept);
    }

    private static List<Edge> merge(Set<UnitEdge> kept) {
        // eixo -> (coordenadas perpendiculares) -> coordenadas ordenadas ao longo do eixo
        List<Map<Long, int[]>> origins = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        List<Map<Long, TreeSet<Integer>>> groups = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        for (UnitEdge e : kept) {
            int[] p = {e.x, e.y, e.z};
            int a = e.axis;
            int u = PERP[a][0];
            int v = PERP[a][1];
            long key = (((long) p[u]) << 32) ^ (p[v] & 0xFFFFFFFFL);
            groups.get(a).computeIfAbsent(key, k -> new TreeSet<>()).add(p[a]);
            origins.get(a).putIfAbsent(key, new int[]{p[u], p[v]});
        }

        List<Edge> result = new ArrayList<>();
        for (int a = 0; a < 3; a++) {
            int u = PERP[a][0];
            int v = PERP[a][1];
            for (Map.Entry<Long, TreeSet<Integer>> entry : groups.get(a).entrySet()) {
                int[] uv = origins.get(a).get(entry.getKey());
                Integer start = null;
                int prev = 0;
                for (int c : entry.getValue()) {
                    if (start == null) {
                        start = c;
                    } else if (c != prev + 1) {
                        result.add(edge(a, u, v, uv, start, prev - start + 1));
                        start = c;
                    }
                    prev = c;
                }
                if (start != null) result.add(edge(a, u, v, uv, start, prev - start + 1));
            }
        }
        return result;
    }

    public static List<Edge> boundingBox(Collection<Vector3i> cells) {
        if (cells.isEmpty()) return List.of();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Vector3i c : cells) {
            min[0] = Math.min(min[0], c.x);
            min[1] = Math.min(min[1], c.y);
            min[2] = Math.min(min[2], c.z);
            max[0] = Math.max(max[0], c.x + 1);
            max[1] = Math.max(max[1], c.y + 1);
            max[2] = Math.max(max[2], c.z + 1);
        }
        List<Edge> result = new ArrayList<>(12);
        for (int a = 0; a < 3; a++) {
            int u = PERP[a][0];
            int v = PERP[a][1];
            int len = max[a] - min[a];
            for (int iu = 0; iu < 2; iu++) {
                for (int iv = 0; iv < 2; iv++) {
                    int[] p = {min[0], min[1], min[2]};
                    p[u] = iu == 0 ? min[u] : max[u];
                    p[v] = iv == 0 ? min[v] : max[v];
                    result.add(new Edge(a, p[0], p[1], p[2], len));
                }
            }
        }
        return result;
    }

    private static boolean has(Set<Vector3i> occupied, int[] p, int a, int u, int v, int du, int dv) {
        int[] q = {p[0], p[1], p[2]};
        q[u] += du;
        q[v] += dv;
        return occupied.contains(new Vector3i(q[0], q[1], q[2]));
    }

    private static Edge edge(int a, int u, int v, int[] uv, int start, int length) {
        int[] p = new int[3];
        p[a] = start;
        p[u] = uv[0];
        p[v] = uv[1];
        return new Edge(a, p[0], p[1], p[2], length);
    }
}
