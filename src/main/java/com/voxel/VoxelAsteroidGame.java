package com.voxel;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class VoxelAsteroidGame {

    private long window;
    private int width = 1280;
    private int height = 720;

    // Параметры астероида
    private static final int GRID_SIZE = 32;
    private List<Float> meshVertices;
    private float rotationAngle = 0.0f;

    public void run() {
        initWindow();
        initOpenGL();
        generateAsteroidMesh();
        mainLoop();
        cleanup();
    }

    private void initWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Не удалось инициализировать GLFW");
        }

        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Voxel Asteroid Engine (OpenWebStart Compatible)", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new RuntimeException("Не удалось создать окно GLFW");
        }

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidmode != null) {
            glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Включаем VSync
        glfwShowWindow(window);
    }

    private void initOpenGL() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);
        glColorMaterial(GL_FRONT_AND_BACK, GL_DIFFUSE);

        // Настройка источника света
        FloatBuffer lightPos = MemoryUtil.memAllocFloat(4).put(new float[]{10.0f, 10.0f, 10.0f, 1.0f}).flip();
        glLightfv(GL_LIGHT0, GL_POSITION, lightPos);
        MemoryUtil.memFree(lightPos);

        glClearColor(0.02f, 0.02f, 0.05f, 1.0f); // Тёмно-космический фон
    }

    private void generateAsteroidMesh() {
        System.out.println("Генерация вокселей и построение 3D-сетки...");
        float[][][] density = new float[GRID_SIZE][GRID_SIZE][GRID_SIZE];
        SimplexNoise noise = new SimplexNoise(12345L);
        float center = GRID_SIZE / 2.0f;
        float radius = 10.0f;

        // 1. Заполнение 3D карты плотности
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    float dx = x - center;
                    float dy = y - center;
                    float dz = z - center;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float n = noise.eval(x * 0.1, y * 0.1, z * 0.1) * 4.0f;
                    density[x][y][z] = (radius + n) - dist;
                }
            }
        }

        // 2. Генерация полигонов кубическим алгоритмом (Naive Surface Reconstruction)
        meshVertices = new ArrayList<>();
        for (int x = 0; x < GRID_SIZE - 1; x++) {
            for (int y = 0; y < GRID_SIZE - 1; y++) {
                for (int z = 0; z < GRID_SIZE - 1; z++) {
                    if (density[x][y][z] > 0) {
                        float px = x - center;
                        float py = y - center;
                        float pz = z - center;

                        // Отрисовка грани куба, если соседний воксель пуст
                        if (x == 0 || density[x - 1][y][z] <= 0) addFace(px, py, pz, -1, 0, 0);
                        if (x == GRID_SIZE - 1 || density[x + 1][y][z] <= 0) addFace(px, py, pz, 1, 0, 0);
                        if (y == 0 || density[x][y - 1][z] <= 0) addFace(px, py, pz, 0, -1, 0);
                        if (y == GRID_SIZE - 1 || density[x][y + 1][z] <= 0) addFace(px, py, pz, 0, 1, 0);
                        if (z == 0 || density[x][y][z - 1] <= 0) addFace(px, py, pz, 0, 0, -1);
                        if (z == GRID_SIZE - 1 || density[x][y][z + 1] <= 0) addFace(px, py, pz, 0, 0, 1);
                    }
                }
            }
        }
        System.out.println("Сгенерировано вертексов: " + meshVertices.size() / 6);
    }

    private void addFace(float x, float y, float z, int nx, int ny, int nz) {
        // Обобщенное добавление полигона грани с нормалями для освещения
        float s = 0.5f;
        float[][] v = new float[4][3];

        if (nx == 1) v = new float[][]{{x+s,y-s,z-s},{x+s,y+s,z-s},{x+s,y+s,z+s},{x+s,y-s,z+s}};
        else if (nx == -1) v = new float[][]{{x-s,y-s,z+s},{x-s,y+s,z+s},{x-s,y+s,z-s},{x-s,y-s,z-s}};
        else if (ny == 1) v = new float[][]{{x-s,y+s,z-s},{x-s,y+s,z+s},{x+s,y+s,z+s},{x+s,y+s,z-s}};
        else if (ny == -1) v = new float[][]{{x-s,y-s,z+s},{x-s,y-s,z-s},{x+s,y-s,z-s},{x+s,y-s,z+s}};
        else if (nz == 1) v = new float[][]{{x-s,y-s,z+s},{x+s,y-s,z+s},{x+s,y+s,z+s},{x-s,y+s,z+s}};
        else if (nz == -1) v = new float[][]{{x-s,y+s,z-s},{x+s,y+s,z-s},{x+s,y-s,z-s},{x-s,y-s,z-s}};

        // Треугольник 1
        addVertex(v[0], nx, ny, nz);
        addVertex(v[1], nx, ny, nz);
        addVertex(v[2], nx, ny, nz);

        // Треугольник 2
        addVertex(v[0], nx, ny, nz);
        addVertex(v[2], nx, ny, nz);
        addVertex(v[3], nx, ny, nz);
    }

    private void addVertex(float[] pos, float nx, float ny, float nz) {
        meshVertices.add(pos[0]); meshVertices.add(pos[1]); meshVertices.add(pos[2]);
        meshVertices.add(nx); meshVertices.add(ny); meshVertices.add(nz);
    }

    private void mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Настройка перспективы 3D Камеры
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            float fov = 45.0f;
            float aspect = (float) width / height;
            float zNear = 0.1f, zFar = 100.0f;
            float fh = (float) Math.tan(Math.toRadians(fov / 2)) * zNear;
            float fw = fh * aspect;
            glFrustum(-fw, fw, -fh, fh, zNear, zFar);

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glTranslatef(0.0f, 0.0f, -30.0f); // Отодвигаем камеру назад

            // Вращение астероида
            rotationAngle += 0.5f;
            glRotatef(rotationAngle, 0.3f, 1.0f, 0.2f);

            // Рендеринг меша астероида
            glColor3f(0.5f, 0.45f, 0.4f); // Серый камень
            glBegin(GL_TRIANGLES);
            for (int i = 0; i < meshVertices.size(); i += 6) {
                glNormal3f(meshVertices.get(i + 3), meshVertices.get(i + 4), meshVertices.get(i + 5));
                glVertex3f(meshVertices.get(i), meshVertices.get(i + 1), meshVertices.get(i + 2));
            }
            glEnd();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void cleanup() {
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public static void main(String[] args) {
        new VoxelAsteroidGame().run();
    }

    // Встроенный быстрый 3D Шум Симплекс
    static class SimplexNoise {
        private final int[] p = new int[512];
        public SimplexNoise(long seed) {
            Random rand = new Random(seed);
            int[] perm = new int[256];
            for (int i = 0; i < 256; i++) perm[i] = i;
            for (int i = 255; i > 0; i--) {
                int j = rand.nextInt(i + 1);
                int temp = perm[i]; perm[i] = perm[j]; perm[j] = temp;
            }
            for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
        }

        public float eval(double xin, double yin, double zin) {
            int X = (int) Math.floor(xin) & 255, Y = (int) Math.floor(yin) & 255, Z = (int) Math.floor(zin) & 255;
            double x = xin - Math.floor(xin), y = yin - Math.floor(yin), z = zin - Math.floor(zin);
            double u = f(x), v = f(y), w = f(z);
            int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
            int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
            return (float) l(w, l(v, l(u, g(p[AA], x, y, z), g(p[BA], x - 1, y, z)),
                    l(u, g(p[AB], x, y - 1, z), g(p[BB], x - 1, y - 1, z))),
                    l(v, l(u, g(p[AA + 1], x, y, z - 1), g(p[BA + 1], x - 1, y, z - 1)),
                    l(u, g(p[AB + 1], x, y - 1, z - 1), g(p[BB + 1], x - 1, y - 1, z - 1))));
        }
        private double f(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        private double l(double t, double a, double b) { return a + t * (b - a); }
        private double g(int h, double x, double y, double z) {
            int hash = h & 15;
            double u = hash < 8 ? x : y, v = hash < 4 ? y : hash == 12 || hash == 14 ? x : z;
            return ((hash & 1) == 0 ? u : -u) + ((hash & 2) == 0 ? v : -v);
        }
    }
}
