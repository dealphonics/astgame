package com.voxel;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

public class VoxelAsteroidGame {

    // Маркер сборки: виден в HUD. Если после обновления его нет — запущен СТАРЫЙ JAR из кэша.
    private static final String BUILD = "BUILD 4";

    private long window;
    private int width = 1280;
    private int height = 720;

    // Параметры астероида
    private static final int GRID_SIZE = 32;
    private static final float CENTER = GRID_SIZE / 2.0f;
    private static final float FOV = 45.0f;
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = 200.0f;

    private float[][][] density;              // изменяемое состояние мира
    private List<Float> meshVertices;         // pos(3) + normal(3) + color(3) на вершину
    private int vertexCount;
    private int solidCount;
    private int vbo;

    // Камера и управление
    private float rotX = -20f;
    private float rotY = 30f;
    private float zoom = 30f;
    private boolean autoRotate = false;       // по умолчанию ВЫКЛ, чтобы удобно строить
    private double lastMouseX, lastMouseY;
    private boolean wasLeftDown, wasRightDown, wasSpaceDown;
    private double plx, ply, prx, pry;        // позиции нажатия для отличия клика от драга

    // Прицел (рейкаст)
    private int[] lastHit;
    private int[] lastPrev;

    // Шейдеры
    private boolean useShaders;
    private int shaderProgram;
    private int locLight;

    public void run() {
        initWindow();
        initOpenGL();
        if (!generateAsteroidMesh()) {
            cleanup();
            return;
        }
        glfwSetWindowTitle(window, "Voxel Asteroid Engine");
        mainLoop();
        cleanup();
    }

    private void initWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Не удалось инициализировать GLFW");
        }

        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4); // Сглаживание (MSAA x4)

        window = glfwCreateWindow(width, height, "Voxel Asteroid Engine (OpenWebStart Compatible)", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new RuntimeException("Не удалось создать окно GLFW");
        }

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidmode != null) {
            glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // VSync

        // Колесо мыши = зум
        glfwSetScrollCallback(window, (w, xoff, yoff) ->
                zoom = Math.max(12f, Math.min(80f, zoom - (float) yoff * 3f)));
    }

    private void initOpenGL() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.02f, 0.02f, 0.05f, 1.0f); // Тёмно-космический фон

        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL20) {
            initShaders();
        }
        System.out.println(BUILD + " | Шейдеры: " + (useShaders ? "включены (GLSL)" : "отключены (фикс. конвейер)"));
    }

    // ========== ШЕЙДЕРЫ ==========

    private static final String VERT_SRC =
            "#version 120\n" +
            "attribute vec3 inPos;\n" +
            "attribute vec3 inNormal;\n" +
            "attribute vec3 inColor;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vObjPos;\n" +
            "varying vec3 vViewPos;\n" +
            "varying vec3 vColor;\n" +
            "void main() {\n" +
            "    vObjPos = inPos;\n" +
            "    vColor = inColor;\n" +
            "    vNormal = gl_NormalMatrix * inNormal;\n" +
            "    vec4 vp = gl_ModelViewMatrix * vec4(inPos, 1.0);\n" +
            "    vViewPos = vp.xyz;\n" +
            "    gl_Position = gl_ModelViewProjectionMatrix * vec4(inPos, 1.0);\n" +
            "}\n";

    private static final String FRAG_SRC =
            "#version 120\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vObjPos;\n" +
            "varying vec3 vViewPos;\n" +
            "varying vec3 vColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "float hash(vec3 p) {\n" +
            "    p = fract(p * 0.3183099 + 0.1);\n" +
            "    p *= 17.0;\n" +
            "    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));\n" +
            "}\n" +
            "float vnoise(vec3 p) {\n" +
            "    vec3 i = floor(p);\n" +
            "    vec3 f = fract(p);\n" +
            "    vec3 u = f * f * (3.0 - 2.0 * f);\n" +
            "    float n000 = hash(i);\n" +
            "    float n100 = hash(i + vec3(1.0, 0.0, 0.0));\n" +
            "    float n010 = hash(i + vec3(0.0, 1.0, 0.0));\n" +
            "    float n110 = hash(i + vec3(1.0, 1.0, 0.0));\n" +
            "    float n001 = hash(i + vec3(0.0, 0.0, 1.0));\n" +
            "    float n101 = hash(i + vec3(1.0, 0.0, 1.0));\n" +
            "    float n011 = hash(i + vec3(0.0, 1.0, 1.0));\n" +
            "    float n111 = hash(i + vec3(1.0, 1.0, 1.0));\n" +
            "    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),\n" +
            "               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);\n" +
            "}\n" +
            "void main() {\n" +
            "    vec3 N = normalize(vNormal);\n" +
            "    float blotch = vnoise(vObjPos * 0.25);\n" +
            "    vec3 base = vColor * (0.80 + 0.40 * blotch);\n" +
            "    vec3 L = normalize(uLightDir);\n" +
            "    vec3 V = normalize(-vViewPos);\n" +
            "    vec3 H = normalize(L + V);\n" +
            "    float diff = max(dot(N, L), 0.0);\n" +
            "    float spec = pow(max(dot(N, H), 0.0), 32.0);\n" +
            "    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);\n" +
            "    vec3 color = base * (0.25 + 0.85 * diff)\n" +
            "               + vec3(0.9, 0.85, 0.8) * spec * 0.35\n" +
            "               + vec3(0.15, 0.18, 0.25) * rim;\n" +
            "    gl_FragColor = vec4(color, 1.0);\n" +
            "}\n";

    private void initShaders() {
        int vs = compileShader(GL_VERTEX_SHADER, VERT_SRC);
        int fs = compileShader(GL_FRAGMENT_SHADER, FRAG_SRC);
        if (vs == 0 || fs == 0) { useShaders = false; return; }

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vs);
        glAttachShader(shaderProgram, fs);
        glBindAttribLocation(shaderProgram, 0, "inPos");
        glBindAttribLocation(shaderProgram, 1, "inNormal");
        glBindAttribLocation(shaderProgram, 2, "inColor");
        glLinkProgram(shaderProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
            System.err.println("Ошибка линковки шейдеров: " + glGetProgramInfoLog(shaderProgram));
            glDeleteProgram(shaderProgram);
            shaderProgram = 0;
            useShaders = false;
            return;
        }
        locLight = glGetUniformLocation(shaderProgram, "uLightDir");
        useShaders = true;
    }

    private int compileShader(int type, String src) {
        int sh = glCreateShader(type);
        glShaderSource(sh, src);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE) {
            System.err.println("Ошибка компиляции шейдера: " + glGetShaderInfoLog(sh));
            glDeleteShader(sh);
            return 0;
        }
        return sh;
    }

    // ========== ЭКРАН ЗАГРУЗКИ ==========

    private void renderLoading(float progress) {
        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        int W = Math.max(fw[0], 1), H = Math.max(fh[0], 1);

        glViewport(0, 0, W, H);
        glDisable(GL_DEPTH_TEST);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrtho(0, W, 0, H, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        glColor3f(0.02f, 0.02f, 0.05f);
        glBegin(GL_QUADS);
        glVertex2f(0, 0); glVertex2f(W, 0); glVertex2f(W, H); glVertex2f(0, H);
        glEnd();

        float px = 4;
        String title = "VOXEL ASTEROID ENGINE";
        drawString(title, (W - title.length() * 4 * px) / 2f, H * 0.66f, px, 0.85f, 0.85f, 0.9f);

        String sub = "GENERATING VOXEL MESH " + (int) (progress * 100) + "%";
        drawString(sub, (W - sub.length() * 4 * (px * 0.75f)) / 2f, H * 0.56f, px * 0.75f, 0.6f, 0.6f, 0.68f);

        float bw = W * 0.6f, bh = 18;
        float bx = (W - bw) / 2f, by = H * 0.44f;
        glColor3f(0.35f, 0.35f, 0.42f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(bx, by); glVertex2f(bx + bw, by); glVertex2f(bx + bw, by + bh); glVertex2f(bx, by + bh);
        glEnd();
        if (progress > 0) {
            glColor3f(0.4f, 0.7f, 1f);
            glBegin(GL_QUADS);
            float fill = Math.max(2f, (bw - 4f) * progress);
            glVertex2f(bx + 2, by + 2); glVertex2f(bx + 2 + fill, by + 2);
            glVertex2f(bx + 2 + fill, by + bh - 2); glVertex2f(bx + 2, by + bh - 2);
            glEnd();
        }

        glfwSwapBuffers(window);
        glfwPollEvents();
    }

    // ========== ГЕНЕРАЦИЯ И ПЕРЕСБОРКА МЕША ==========

    private boolean generateAsteroidMesh() {
        System.out.println(BUILD + " | Генерация вокселей и построение 3D-сетки...");
        renderLoading(0.02f);
        if (glfwWindowShouldClose(window)) return false;

        density = new float[GRID_SIZE][GRID_SIZE][GRID_SIZE];
        SimplexNoise noise = new SimplexNoise(12345L);
        float radius = 10.0f;

        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    float dx = x - CENTER, dy = y - CENTER, dz = z - CENTER;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float n = noise.eval(x * 0.1, y * 0.1, z * 0.1) * 4.0f;
                    density[x][y][z] = (radius + n) - dist;
                }
            }
        }

        buildFaces();
        buildVBO();
        renderLoading(1f);
        return true;
    }

    // Построение граней из текущей сетки плотности (используется и при пересборке)
    private void buildFaces() {
        meshVertices = new ArrayList<>();
        solidCount = 0;
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    if (density[x][y][z] > 0) {
                        solidCount++;
                        float px = x - CENTER, py = y - CENTER, pz = z - CENTER;
                        if (x == 0 || density[x - 1][y][z] <= 0) addFace(x, y, z, 0, px, py, pz, -1, 0, 0);
                        if (x == GRID_SIZE - 1 || density[x + 1][y][z] <= 0) addFace(x, y, z, 1, px, py, pz, 1, 0, 0);
                        if (y == 0 || density[x][y - 1][z] <= 0) addFace(x, y, z, 2, px, py, pz, 0, -1, 0);
                        if (y == GRID_SIZE - 1 || density[x][y + 1][z] <= 0) addFace(x, y, z, 3, px, py, pz, 0, 1, 0);
                        if (z == 0 || density[x][y][z - 1] <= 0) addFace(x, y, z, 4, px, py, pz, 0, 0, -1);
                        if (z == GRID_SIZE - 1 || density[x][y][z + 1] <= 0) addFace(x, y, z, 5, px, py, pz, 0, 0, 1);
                    }
                }
            }
        }
        vertexCount = meshVertices.size() / 9;
    }

    private void rebuildMesh() {
        buildFaces();
        buildVBO();
    }

    // Детерминированный цвет грани: одинаков для всех 6 вершин грани
    private float[] faceColor(int x, int y, int z, int dir) {
        long seed = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ ((dir + 1) * 2654435761L);
        Random r = new Random(seed);
        float t = r.nextFloat();
        float cr = 0.34f + 0.24f * t;
        float cg = 0.29f + 0.23f * t;
        float cb = 0.25f + 0.21f * t;
        float shade = 0.90f + 0.10f * (dir / 5.0f);
        return new float[]{cr * shade, cg * shade, cb * shade};
    }

    private void addFace(int ix, int iy, int iz, int dir, float x, float y, float z, int nx, int ny, int nz) {
        float s = 0.5f;
        float[][] v = new float[4][3];
        float[] col = faceColor(ix, iy, iz, dir);

        if (nx == 1) v = new float[][]{{x+s,y-s,z-s},{x+s,y+s,z-s},{x+s,y+s,z+s},{x+s,y-s,z+s}};
        else if (nx == -1) v = new float[][]{{x-s,y-s,z+s},{x-s,y+s,z+s},{x-s,y+s,z-s},{x-s,y-s,z-s}};
        else if (ny == 1) v = new float[][]{{x-s,y+s,z-s},{x-s,y+s,z+s},{x+s,y+s,z+s},{x+s,y+s,z-s}};
        else if (ny == -1) v = new float[][]{{x-s,y-s,z+s},{x-s,y-s,z-s},{x+s,y-s,z-s},{x+s,y-s,z+s}};
        else if (nz == 1) v = new float[][]{{x-s,y-s,z+s},{x+s,y-s,z+s},{x+s,y+s,z+s},{x-s,y+s,z+s}};
        else if (nz == -1) v = new float[][]{{x-s,y+s,z-s},{x+s,y+s,z-s},{x+s,y-s,z-s},{x-s,y-s,z-s}};

        addVertex(v[0], nx, ny, nz, col); addVertex(v[1], nx, ny, nz, col); addVertex(v[2], nx, ny, nz, col);
        addVertex(v[0], nx, ny, nz, col); addVertex(v[2], nx, ny, nz, col); addVertex(v[3], nx, ny, nz, col);
    }

    private void addVertex(float[] pos, float nx, float ny, float nz, float[] col) {
        meshVertices.add(pos[0]); meshVertices.add(pos[1]); meshVertices.add(pos[2]);
        meshVertices.add(nx); meshVertices.add(ny); meshVertices.add(nz);
        meshVertices.add(col[0]); meshVertices.add(col[1]); meshVertices.add(col[2]);
    }

    private void buildVBO() {
        FloatBuffer fb = BufferUtils.createFloatBuffer(meshVertices.size());
        for (float f : meshVertices) fb.put(f);
        fb.flip();
        meshVertices = null;

        if (vbo != 0) glDeleteBuffers(vbo);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    // ========== РЕЙКАСТ (DDA по воксельной сетке) ==========

    private static float[] rotXV(float[] v, float deg) {
        float r = (float) Math.toRadians(deg), c = (float) Math.cos(r), s = (float) Math.sin(r);
        return new float[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }

    private static float[] rotYV(float[] v, float deg) {
        float r = (float) Math.toRadians(deg), c = (float) Math.cos(r), s = (float) Math.sin(r);
        return new float[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    private void updateTarget() {
        lastHit = null;
        lastPrev = null;

        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        int W = Math.max(fw[0], 1), H = Math.max(fh[0], 1);
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);

        // Луч в view-space через пиксель под курсором
        float nx = (float) (2.0 * mx[0] / W - 1.0);
        float ny = (float) (1.0 - 2.0 * my[0] / H);
        float fh2 = (float) Math.tan(Math.toRadians(FOV / 2)) * Z_NEAR;
        float fw2 = fh2 * ((float) W / H);

        float dx = nx * fw2, dy = ny * fh2, dz = -Z_NEAR;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float[] rd = {dx / len, dy / len, dz / len};

        // Перевод луча в object-space: p_obj = Ry(-rotY) * Rx(-rotX) * T(+zoom) * p_view
        float[] o = rotYV(rotXV(new float[]{0, 0, zoom}, -rotX), -rotY);
        float[] d = rotYV(rotXV(rd, -rotX), -rotY);

        // Сдвигаем в пространство, где границы ячеек — целые числа
        float ux = o[0] + CENTER + 0.5f, uy = o[1] + CENTER + 0.5f, uz = o[2] + CENTER + 0.5f;
        int x = (int) Math.floor(ux), y = (int) Math.floor(uy), z = (int) Math.floor(uz);
        int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
        float tdx = d[0] != 0 ? Math.abs(1f / d[0]) : Float.MAX_VALUE;
        float tdy = d[1] != 0 ? Math.abs(1f / d[1]) : Float.MAX_VALUE;
        float tdz = d[2] != 0 ? Math.abs(1f / d[2]) : Float.MAX_VALUE;
        float tmx = d[0] != 0 ? (d[0] > 0 ? (x + 1 - ux) : (ux - x)) * tdx : Float.MAX_VALUE;
        float tmy = d[1] != 0 ? (d[1] > 0 ? (y + 1 - uy) : (uy - y)) * tdy : Float.MAX_VALUE;
        float tmz = d[2] != 0 ? (d[2] > 0 ? (z + 1 - uz) : (uz - z)) * tdz : Float.MAX_VALUE;

        int[] prev = null;
        float t = 0;
        for (int i = 0; i < 400 && t < 300; i++) {
            if (x >= 0 && y >= 0 && z >= 0 && x < GRID_SIZE && y < GRID_SIZE && z < GRID_SIZE
                    && density[x][y][z] > 0) {
                lastHit = new int[]{x, y, z};
                lastPrev = prev;
                return;
            }
            prev = new int[]{x, y, z};
            if (tmx <= tmy && tmx <= tmz) { x += sx; t = tmx; tmx += tdx; }
            else if (tmy <= tmz) { y += sy; t = tmy; tmy += tdy; }
            else { z += sz; t = tmz; tmz += tdz; }
        }
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < GRID_SIZE && y < GRID_SIZE && z < GRID_SIZE;
    }

    private void mine() {
        updateTarget();
        if (lastHit != null && density[lastHit[0]][lastHit[1]][lastHit[2]] > 0) {
            density[lastHit[0]][lastHit[1]][lastHit[2]] = -1;
            rebuildMesh();
        }
    }

    private void buildBlock() {
        updateTarget();
        if (lastPrev != null && inBounds(lastPrev[0], lastPrev[1], lastPrev[2])
                && density[lastPrev[0]][lastPrev[1]][lastPrev[2]] <= 0) {
            density[lastPrev[0]][lastPrev[1]][lastPrev[2]] = 1;
            rebuildMesh();
        }
    }

    // ========== ГЛАВНЫЙ ЦИКЛ ==========

    private void mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            handleInput();
            updateTarget();

            int[] fw = new int[1], fh = new int[1];
            glfwGetFramebufferSize(window, fw, fh);
            int W = Math.max(fw[0], 1), H = Math.max(fh[0], 1);

            glViewport(0, 0, W, H);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Перспектива 3D камеры
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            float aspect = (float) W / H;
            float fh2 = (float) Math.tan(Math.toRadians(FOV / 2)) * Z_NEAR;
            float fw2 = fh2 * aspect;
            glFrustum(-fw2, fw2, -fh2, fh2, Z_NEAR, Z_FAR);

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glTranslatef(0.0f, 0.0f, -zoom);
            glRotatef(rotX, 1.0f, 0.0f, 0.0f);
            glRotatef(rotY, 0.0f, 1.0f, 0.0f);

            if (useShaders) {
                glUseProgram(shaderProgram);
                glUniform3f(locLight, 0.5f, 0.8f, 0.6f);
            } else {
                glEnable(GL_LIGHTING);
                glEnable(GL_LIGHT0);
                glEnable(GL_COLOR_MATERIAL);
                glColorMaterial(GL_FRONT_AND_BACK, GL_DIFFUSE);
            }

            // pos(3) + normal(3) + color(3) = 9 float = 36 байт
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            if (useShaders) {
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, 3, GL_FLOAT, false, 36, 12);
                glEnableVertexAttribArray(2);
                glVertexAttribPointer(2, 3, GL_FLOAT, false, 36, 24);
            } else {
                glEnableClientState(GL_VERTEX_ARRAY);
                glEnableClientState(GL_NORMAL_ARRAY);
                glEnableClientState(GL_COLOR_ARRAY);
                glVertexPointer(3, GL_FLOAT, 36, 0);
                glNormalPointer(GL_FLOAT, 36, 12);
                glColorPointer(3, GL_FLOAT, 36, 24);
            }
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);

            if (useShaders) {
                glDisableVertexAttribArray(0);
                glDisableVertexAttribArray(1);
                glDisableVertexAttribArray(2);
                glUseProgram(0);
            } else {
                glDisableClientState(GL_VERTEX_ARRAY);
                glDisableClientState(GL_NORMAL_ARRAY);
                glDisableClientState(GL_COLOR_ARRAY);
                glDisable(GL_LIGHTING);
            }
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            drawHighlight();
            drawHud(W, H);

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    // Жёлтая рамка вокруг вокселя под прицелом
    private void drawHighlight() {
        if (lastHit == null) return;
        glDisable(GL_LIGHTING);
        glUseProgram(0);
        glColor3f(1.0f, 0.9f, 0.3f);
        float h = 0.502f;
        float cx = lastHit[0] - CENTER, cy = lastHit[1] - CENTER, cz = lastHit[2] - CENTER;
        float x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h, z0 = cz - h, z1 = cz + h;
        glBegin(GL_LINES);
        // нижняя грань
        glVertex3f(x0,y0,z0); glVertex3f(x1,y0,z0);
        glVertex3f(x1,y0,z0); glVertex3f(x1,y0,z1);
        glVertex3f(x1,y0,z1); glVertex3f(x0,y0,z1);
        glVertex3f(x0,y0,z1); glVertex3f(x0,y0,z0);
        // верхняя грань
        glVertex3f(x0,y1,z0); glVertex3f(x1,y1,z0);
        glVertex3f(x1,y1,z0); glVertex3f(x1,y1,z1);
        glVertex3f(x1,y1,z1); glVertex3f(x0,y1,z1);
        glVertex3f(x0,y1,z1); glVertex3f(x0,y1,z0);
        // вертикали
        glVertex3f(x0,y0,z0); glVertex3f(x0,y1,z0);
        glVertex3f(x1,y0,z0); glVertex3f(x1,y1,z0);
        glVertex3f(x1,y0,z1); glVertex3f(x1,y1,z1);
        glVertex3f(x0,y0,z1); glVertex3f(x0,y1,z1);
        glEnd();
    }

    private void handleInput() {
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        boolean left = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean right = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        // Запоминаем позицию нажатия
        if (left && !wasLeftDown) { plx = mx[0]; ply = my[1 - 1]; }
        if (right && !wasRightDown) { prx = mx[0]; pry = my[0]; pry = my[1]; }

        // Клик (без драга): ЛКМ — майнинг, ПКМ — строительство
        if (!left && wasLeftDown) {
            double ddx = mx[0] - plx, ddy = my[0] - ply;
            if (ddx * ddx + ddy * ddy < 36) mine();
        }
        if (!right && wasRightDown) {
            double ddx = mx[0] - prx, ddy = my[0] - pry;
            if (ddx * ddx + ddy * ddy < 36) buildBlock();
        }

        // Вращение камеры перетаскиванием (любой кнопкой)
        boolean held = (left && wasLeftDown) || (right && wasRightDown);
        if (held) {
            rotY += (mx[0] - lastMouseX) * 0.4f;
            rotX += (my[0] - lastMouseY) * 0.4f;
            rotX = Math.max(-89f, Math.min(89f, rotX));
        }
        wasLeftDown = left;
        wasRightDown = right;
        lastMouseX = mx[0];
        lastMouseY = my[0];

        // Пробел = переключение автовращения
        boolean space = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (space && !wasSpaceDown) autoRotate = !autoRotate;
        wasSpaceDown = space;

        if (autoRotate) rotY += 0.3f;
    }

    private void drawHud(int W, int H) {
        glDisable(GL_DEPTH_TEST);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, W, 0, H, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        drawString("LMB: MINE   RMB: BUILD   DRAG: ROTATE   WHEEL: ZOOM",
                12, 30, 2.5f, 0.7f, 0.75f, 0.8f);
        drawString("SPACE: AUTO " + (autoRotate ? "ON" : "OFF") + "   VOXELS: " + solidCount +
                "   " + (useShaders ? "SHADERS" : "FIXED") + "   " + BUILD,
                12, 52, 2.5f, 0.45f, 0.6f, 0.7f);

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
        glEnable(GL_DEPTH_TEST);
    }

    // ========== ПИКСЕЛЬНЫЙ ШРИФТ 3x5 ==========

    private static final Map<Character, int[]> FONT = new HashMap<>();
    static {
        put('A',2,5,7,5,5); put('B',6,5,6,5,6); put('C',3,4,4,4,3); put('D',6,5,5,5,6);
        put('E',7,4,6,4,7); put('F',7,4,6,4,4); put('G',3,4,5,5,3); put('H',5,5,7,5,5);
        put('I',7,2,2,2,7); put('J',1,1,1,5,2); put('K',5,5,6,5,5); put('L',4,4,4,4,7);
        put('M',5,7,7,5,5); put('N',6,5,5,5,5); put('O',7,5,5,5,7); put('P',6,5,6,4,4);
        put('Q',7,5,5,7,1); put('R',6,5,6,5,5); put('S',3,4,2,1,6); put('T',7,2,2,2,2);
        put('U',5,5,5,5,7); put('V',5,5,5,5,2); put('W',5,5,7,7,5); put('X',5,5,2,5,5);
        put('Y',5,5,2,2,2); put('Z',7,1,2,4,7);
        put('0',7,5,5,5,7); put('1',2,6,2,2,7); put('2',6,1,2,4,7); put('3',7,1,3,1,7);
        put('4',5,5,7,1,1); put('5',7,4,6,1,6); put('6',7,4,7,5,7); put('7',7,1,1,2,2);
        put('8',7,5,7,5,7); put('9',7,5,7,1,7);
        put(' ',0,0,0,0,0); put(':',0,2,0,2,0); put('-',0,0,7,0,0); put('.',0,0,0,0,2);
        put('%',5,1,2,4,5); put('/',1,1,2,4,4); put('+',0,2,7,2,0);
    }
    private static void put(char c, int... rows) { FONT.put(c, rows); }

    private void drawString(String s, float x, float y, float px, float r, float g, float b) {
        glColor3f(r, g, b);
        glBegin(GL_QUADS);
        float cx = x;
        for (char ch : s.toUpperCase().toCharArray()) {
            int[] glyph = FONT.get(ch);
            if (glyph != null) {
                for (int row = 0; row < 5; row++) {
                    int bits = glyph[row];
                    for (int col = 0; col < 3; col++) {
                        if ((bits & (1 << (2 - col))) != 0) {
                            float qx = cx + col * px, qy = y - row * px;
                            glVertex2f(qx, qy);
                            glVertex2f(qx + px, qy);
                            glVertex2f(qx + px, qy - px);
                            glVertex2f(qx, qy - px);
                        }
                    }
                }
            }
            cx += 4 * px;
        }
        glEnd();
    }

    private void cleanup() {
        if (vbo != 0) glDeleteBuffers(vbo);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);
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
