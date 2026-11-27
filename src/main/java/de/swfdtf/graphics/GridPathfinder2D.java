package de.swfdtf.graphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Deterministic 2D A* grid pathfinder allowing companion characters and actors to navigate
 * around obstacles inside top-down rooms ({@code #0056}).
 */
public final class GridPathfinder2D {

    private static final int ORTHOGONAL_COST = 10;
    private static final int DIAGONAL_COST = 14;
    private static final int[][] DIRECTIONS_4 = {
        {0, -1}, {1, 0}, {0, 1}, {-1, 0}
    };
    private static final int[][] DIRECTIONS_8 = {
        {0, -1}, {1, 0}, {0, 1}, {-1, 0},
        {1, -1}, {1, 1}, {-1, 1}, {-1, -1}
    };

    /**
     * Discrete 2D tile coordinate on a {@link GridMap}.
     *
     * @param x zero-based horizontal grid coordinate
     * @param y zero-based vertical grid coordinate
     */
    public record GridCell(int x, int y) {
    }

    /**
     * Mutable 2D walkability grid defining room dimensions and blocked obstacle cells.
     */
    public static final class GridMap {

        private final int width;
        private final int height;
        private final boolean[] blocked;

        /**
         * Creates an unobstructed {@code GridMap} of the given dimensions.
         *
         * @param width  grid width in cells ({@code > 0})
         * @param height grid height in cells ({@code > 0})
         */
        public GridMap(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Grid dimensions must be positive: " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
            this.blocked = new boolean[Math.multiplyExact(width, height)];
        }

        /**
         * Returns the horizontal cell count.
         *
         * @return grid width in cells
         */
        public int getWidth() {
            return width;
        }

        /**
         * Returns the vertical cell count.
         *
         * @return grid height in cells
         */
        public int getHeight() {
            return height;
        }

        /**
         * Checks whether {@code (x, y)} lies inside the grid bounds.
         *
         * @param x X coordinate
         * @param y Y coordinate
         * @return {@code true} if within bounds
         */
        public boolean isInBounds(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height;
        }

        /**
         * Marks or unmarks cell {@code (x, y)} as blocked.
         *
         * @param x       X coordinate
         * @param y       Y coordinate
         * @param isSolid {@code true} to block walkability
         * @return {@code this} for fluent chaining
         */
        public GridMap setBlocked(int x, int y, boolean isSolid) {
            validateBounds(x, y);
            blocked[y * width + x] = isSolid;
            return this;
        }

        /**
         * Returns whether cell {@code (x, y)} is blocked (out-of-bounds is treated as blocked).
         *
         * @param x X coordinate
         * @param y Y coordinate
         * @return {@code true} if blocked or out of bounds
         */
        public boolean isBlocked(int x, int y) {
            return !isInBounds(x, y) || blocked[y * width + x];
        }

        /**
         * Returns whether cell {@code (x, y)} is inside bounds and unblocked.
         *
         * @param x X coordinate
         * @param y Y coordinate
         * @return {@code true} if walkable
         */
        public boolean isWalkable(int x, int y) {
            return isInBounds(x, y) && !blocked[y * width + x];
        }

        private void validateBounds(int x, int y) {
            if (!isInBounds(x, y)) {
                throw new IllegalArgumentException(
                        "Grid coordinate out of bounds: (" + x + ", " + y + ") for " + width + "x" + height
                );
            }
        }
    }

    private record SearchNode(GridCell cell, int gCost, int hCost, int fCost) {
    }

    private GridPathfinder2D() {
    }

    /**
     * Finds the shortest 4-directional walkable path from {@code start} to {@code goal}.
     *
     * @param grid  walkability grid (must not be {@code null})
     * @param start start coordinate (must not be {@code null})
     * @param goal  goal coordinate (must not be {@code null})
     * @return shortest path inclusive of {@code start} and {@code goal}, or {@link Optional#empty()} if blocked
     */
    public static Optional<List<GridCell>> findPath(GridMap grid, GridCell start, GridCell goal) {
        return findPath(grid, start, goal, false);
    }

    /**
     * Finds the shortest walkable path from {@code start} to {@code goal} with optional
     * diagonal movement (enforcing strict corner-cutting prevention).
     *
     * @param grid          walkability grid (must not be {@code null})
     * @param start         start coordinate (must not be {@code null})
     * @param goal          goal coordinate (must not be {@code null})
     * @param allowDiagonal {@code true} to allow 8-way diagonal steps when adjacent edges are walkable
     * @return shortest path inclusive of {@code start} and {@code goal}, or {@link Optional#empty()} if blocked
     */
    public static Optional<List<GridCell>> findPath(
            GridMap grid,
            GridCell start,
            GridCell goal,
            boolean allowDiagonal
    ) {
        Objects.requireNonNull(grid, "grid must not be null");
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(goal, "goal must not be null");

        if (!grid.isWalkable(start.x(), start.y()) || !grid.isWalkable(goal.x(), goal.y())) {
            return Optional.empty();
        }
        if (start.equals(goal)) {
            return Optional.of(List.of(start));
        }

        int[][] directions = allowDiagonal ? DIRECTIONS_8 : DIRECTIONS_4;
        PriorityQueue<SearchNode> open = new PriorityQueue<>(
                Comparator.comparingInt(SearchNode::fCost)
                        .thenComparingInt(SearchNode::hCost)
                        .thenComparingInt(n -> n.cell().y())
                        .thenComparingInt(n -> n.cell().x())
        );
        Map<GridCell, GridCell> cameFrom = new HashMap<>();
        Map<GridCell, Integer> gScores = new HashMap<>();

        int startH = computeHeuristic(start, goal, allowDiagonal);
        gScores.put(start, 0);
        open.add(new SearchNode(start, 0, startH, startH));

        while (!open.isEmpty()) {
            SearchNode current = open.poll();
            GridCell currCell = current.cell();
            if (current.gCost() > gScores.getOrDefault(currCell, Integer.MAX_VALUE)) {
                continue;
            }
            if (currCell.equals(goal)) {
                return Optional.of(reconstructPath(cameFrom, goal));
            }

            for (int[] dir : directions) {
                int nx = currCell.x() + dir[0];
                int ny = currCell.y() + dir[1];
                if (!grid.isWalkable(nx, ny)) {
                    continue;
                }
                boolean isDiag = dir[0] != 0 && dir[1] != 0;
                if (isDiag && (!grid.isWalkable(currCell.x() + dir[0], currCell.y())
                        || !grid.isWalkable(currCell.x(), currCell.y() + dir[1]))) {
                    continue;
                }

                GridCell neighbor = new GridCell(nx, ny);
                int stepCost = isDiag ? DIAGONAL_COST : ORTHOGONAL_COST;
                int tentativeG = current.gCost() + stepCost;
                if (tentativeG < gScores.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    cameFrom.put(neighbor, currCell);
                    gScores.put(neighbor, tentativeG);
                    int h = computeHeuristic(neighbor, goal, allowDiagonal);
                    open.add(new SearchNode(neighbor, tentativeG, h, tentativeG + h));
                }
            }
        }
        return Optional.empty();
    }

    private static int computeHeuristic(GridCell a, GridCell b, boolean allowDiagonal) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        if (!allowDiagonal) {
            return (dx + dy) * ORTHOGONAL_COST;
        }
        int min = Math.min(dx, dy);
        int max = Math.max(dx, dy);
        return min * DIAGONAL_COST + (max - min) * ORTHOGONAL_COST;
    }

    private static List<GridCell> reconstructPath(Map<GridCell, GridCell> cameFrom, GridCell goal) {
        List<GridCell> path = new ArrayList<>();
        GridCell cursor = goal;
        while (cursor != null) {
            path.add(cursor);
            cursor = cameFrom.get(cursor);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }
}
