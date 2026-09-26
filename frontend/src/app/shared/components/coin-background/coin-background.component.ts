import {
  Component,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnDestroy,
  NgZone,
  inject,
  PLATFORM_ID,
  ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { SavingsJarService } from '../../../core/services/savings-jar.service';
import type * as THREE from 'three';
import type RAPIER from '@dimforge/rapier3d-compat';
import type { RoomEnvironment as RoomEnvType } from 'three/examples/jsm/environments/RoomEnvironment.js';

interface CoinItem {
  id: number;
  instanceIndex: number;
  body: RAPIER.RigidBody;
  collider: RAPIER.Collider;
  baseScale: number;
  currentScale: number;
  spawnTime: number;
  landedTime: number | null;
  fadeStartTime: number | null;
  state: 'falling' | 'resting' | 'fading';
}

@Component({
  selector: 'app-coin-background',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <canvas
      #coinCanvas
      class="absolute top-0 left-0 w-full h-full pointer-events-none z-0 overflow-hidden"
    ></canvas>
  `,
  styles: [`
    :host {
      display: block;
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      pointer-events: none;
      z-index: 0;
      overflow: hidden;
    }
  `]
})
export class CoinBackgroundComponent implements AfterViewInit, OnDestroy {
  @ViewChild('coinCanvas', { static: true })
  canvasRef!: ElementRef<HTMLCanvasElement>;

  private ngZone = inject(NgZone);
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);
  private savingsJarService = inject(SavingsJarService);

  // Configuration
  private readonly PIXELS_PER_UNIT = 35; // 1 physics unit = 35 screen pixels
  private readonly COIN_RADIUS = 0.70;  // ~49px diameter
  private readonly COIN_HEIGHT = 0.16;  // ~5.6px thickness
  private readonly REST_DURATION = 2500; // 2.5s resting at footer before fade-out
  private readonly FADE_DURATION = 800;  // 0.8s smooth scale-down fade
  private maxCoins = 18;

  // Dynamic Module References (Loaded on demand to prevent bundling in initial/admin chunks)
  private THREE!: typeof THREE;
  private RAPIER!: typeof RAPIER;
  private RoomEnvironmentClass!: typeof RoomEnvType;

  // Three.js Core
  private scene?: THREE.Scene;
  private camera?: THREE.OrthographicCamera;
  private renderer?: THREE.WebGLRenderer;
  private pmremGenerator?: THREE.PMREMGenerator;
  private envMap?: THREE.Texture;
  private coinGeometry?: THREE.CylinderGeometry;
  private coinMaterial?: THREE.MeshStandardMaterial;
  private instancedMesh?: THREE.InstancedMesh;

  // Rapier Physics
  private rapierInitialized = false;
  private world?: RAPIER.World;
  private floorBody?: RAPIER.RigidBody;
  private floorCollider?: RAPIER.Collider;
  private leftWallBody?: RAPIER.RigidBody;
  private rightWallBody?: RAPIER.RigidBody;
  private frontWallBody?: RAPIER.RigidBody;
  private backWallBody?: RAPIER.RigidBody;

  // Coin Management
  private coins: (CoinItem | null)[] = [];
  private lastSpawnTime = 0;
  private spawnInterval = 1300; // ms

  // Dimension tracking
  private canvasWidth = 1200;
  private canvasHeight = 3500;
  private worldWidth = 34;
  private worldHeight = 100;
  private floorY = -95;

  // Interaction State
  private raycaster!: THREE.Raycaster;
  private mouseNdc!: THREE.Vector2;
  private draggedCoin: CoinItem | null = null;
  private hoveredIndex: number | null = null;
  private lastMouseWorldX = 0;
  private lastMouseWorldY = 0;
  private mouseVelocityX = 0;
  private mouseVelocityY = 0;

  // Animation & Cleanup
  private animFrameId?: number;
  private resizeObserver?: ResizeObserver;
  private windowListeners: { type: string; listener: EventListener }[] = [];
  private isDestroyed = false;

  // Reusable Math Objects for Frame Loop (zero garbage collection overhead)
  private tempMatrix!: THREE.Matrix4;
  private tempPosition!: THREE.Vector3;
  private tempQuaternion!: THREE.Quaternion;
  private tempScale!: THREE.Vector3;
  private defaultColor!: THREE.Color;
  private hoverColor!: THREE.Color;

  async ngAfterViewInit(): Promise<void> {
    if (!this.isBrowser) return;

    // STEP 1: Check accessibility: prefers-reduced-motion
    const prefersReducedMotion =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (prefersReducedMotion) {
      return;
    }

    // Adapt max coins for mobile viewports
    if (window.innerWidth < 768) {
      this.maxCoins = 8;
      this.spawnInterval = 2000;
    }

    try {
      // Dynamic import of Three.js and Rapier WASM Physics
      const [threeMod, envMod, rapierMod] = await Promise.all([
        import('three'),
        import('three/examples/jsm/environments/RoomEnvironment.js'),
        import('@dimforge/rapier3d-compat')
      ]);

      this.THREE = threeMod;
      this.RoomEnvironmentClass = envMod.RoomEnvironment;
      this.RAPIER = (rapierMod.default || rapierMod) as typeof RAPIER;

      if (!this.rapierInitialized) {
        await this.RAPIER.init();
        this.rapierInitialized = true;
      }

      if (this.isDestroyed) return;

      // Measure & Initialize Three.js & Physics World outside Angular Zone
      this.ngZone.runOutsideAngular(() => {
        this.initWorldAndRenderer();
        this.seedInitialCoins();
        this.setupEventListeners();
        this.animate();
      });
    } catch (err) {
      console.warn('Campus Coin 3D background init skipped:', err);
    }
  }

  private initWorldAndRenderer(): void {
    const canvas = this.canvasRef.nativeElement;
    const parentContainer = canvas.parentElement || document.body;

    this.updateDimensions(parentContainer);

    // Initialize math helpers
    this.tempMatrix = new this.THREE.Matrix4();
    this.tempPosition = new this.THREE.Vector3();
    this.tempQuaternion = new this.THREE.Quaternion();
    this.tempScale = new this.THREE.Vector3();
    this.defaultColor = new this.THREE.Color(0xF59E0B);
    this.hoverColor = new this.THREE.Color(0xFDE047);
    this.raycaster = new this.THREE.Raycaster();
    this.mouseNdc = new this.THREE.Vector2();

    // 1. Three.js Scene
    this.scene = new this.THREE.Scene();

    // 2. Orthographic Camera: Camera at (0, 0, 50), lookAt (0, 0, 0)
    this.camera = new this.THREE.OrthographicCamera(
      -this.worldWidth / 2,
      this.worldWidth / 2,
      0,
      -this.worldHeight,
      1,
      200
    );
    this.camera.position.set(0, 0, 50);
    this.camera.lookAt(0, 0, 0);

    // 3. WebGL Renderer with Alpha & Capped PixelRatio
    this.renderer = new this.THREE.WebGLRenderer({
      canvas,
      alpha: true,
      antialias: true,
      powerPreference: 'high-performance'
    });
    this.renderer.setSize(this.canvasWidth, this.canvasHeight, false);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    this.renderer.toneMapping = this.THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.2;

    // 4. Photorealistic Metallic Reflections via RoomEnvironment
    this.pmremGenerator = new this.THREE.PMREMGenerator(this.renderer);
    const room = new this.RoomEnvironmentClass();
    this.envMap = this.pmremGenerator.fromScene(room).texture;
    this.scene.environment = this.envMap;

    // 5. Lighting
    const ambientLight = new this.THREE.AmbientLight(0xfffbeb, 1.4);
    this.scene.add(ambientLight);

    const dirLight1 = new this.THREE.DirectionalLight(0xfffaed, 2.8);
    dirLight1.position.set(15, 25, 30);
    this.scene.add(dirLight1);

    const dirLight2 = new this.THREE.DirectionalLight(0xfef08a, 1.4);
    dirLight2.position.set(-15, -20, 20);
    this.scene.add(dirLight2);

    // 6. Coin Geometry & Gold Metallic Material
    this.coinGeometry = new this.THREE.CylinderGeometry(
      this.COIN_RADIUS,
      this.COIN_RADIUS,
      this.COIN_HEIGHT,
      32
    );

    this.coinMaterial = new this.THREE.MeshStandardMaterial({
      color: 0xFBBF24,
      metalness: 0.90,
      roughness: 0.24,
      envMapIntensity: 1.5
    });

    // 7. InstancedMesh for high performance
    this.instancedMesh = new this.THREE.InstancedMesh(
      this.coinGeometry,
      this.coinMaterial,
      this.maxCoins
    );
    this.instancedMesh.instanceMatrix.setUsage(this.THREE.DynamicDrawUsage);

    // Initialize all instances as hidden/offscreen
    const offscreenMatrix = new this.THREE.Matrix4().setPosition(0, 1000, 0);
    for (let i = 0; i < this.maxCoins; i++) {
      this.instancedMesh.setMatrixAt(i, offscreenMatrix);
      this.instancedMesh.setColorAt(i, this.defaultColor);
    }
    this.instancedMesh.instanceMatrix.needsUpdate = true;
    if (this.instancedMesh.instanceColor) {
      this.instancedMesh.instanceColor.needsUpdate = true;
    }
    this.scene.add(this.instancedMesh);

    // 8. Rapier Physics World with gentle downward gravity
    const gravity = { x: 0.0, y: -4.2, z: 0.0 };
    this.world = new this.RAPIER.World(gravity);

    // 9. Static Invisible Boundaries & Footer Floor Collider
    this.setupPhysicsBoundaries();

    // Prepare slots array
    this.coins = new Array(this.maxCoins).fill(null);
  }

  private updateDimensions(parentContainer: HTMLElement): void {
    this.canvasWidth = Math.max(window.innerWidth, parentContainer.clientWidth || 1200);
    this.canvasHeight = Math.max(
      document.documentElement.scrollHeight,
      parentContainer.scrollHeight || 3500
    );

    this.worldWidth = this.canvasWidth / this.PIXELS_PER_UNIT;
    this.worldHeight = this.canvasHeight / this.PIXELS_PER_UNIT;

    const footer = document.querySelector('app-landing-footer');
    let footerOffsetTop = this.canvasHeight - 240;
    if (footer instanceof HTMLElement) {
      const rect = footer.getBoundingClientRect();
      footerOffsetTop = rect.top + window.scrollY;
    }
    this.floorY = -(footerOffsetTop / this.PIXELS_PER_UNIT);

    const canvas = this.canvasRef.nativeElement;
    if (canvas) {
      canvas.style.width = '100%';
      canvas.style.height = `${this.canvasHeight}px`;
    }
  }

  private setupPhysicsBoundaries(): void {
    if (!this.world) return;

    // 1. Floor at the top edge of the footer section
    const floorBodyDesc = this.RAPIER.RigidBodyDesc.fixed().setTranslation(0, this.floorY, 0);
    this.floorBody = this.world.createRigidBody(floorBodyDesc);
    const floorColliderDesc = this.RAPIER.ColliderDesc.cuboid(this.worldWidth + 10, 0.5, 8)
      .setRestitution(0.35)
      .setFriction(0.7);
    this.floorCollider = this.world.createCollider(floorColliderDesc, this.floorBody);

    // 2. Left side wall
    const leftWallDesc = this.RAPIER.RigidBodyDesc.fixed().setTranslation(
      -this.worldWidth / 2 - 0.5,
      -this.worldHeight / 2,
      0
    );
    this.leftWallBody = this.world.createRigidBody(leftWallDesc);
    this.world.createCollider(
      this.RAPIER.ColliderDesc.cuboid(0.5, this.worldHeight + 10, 8),
      this.leftWallBody
    );

    // 3. Right side wall
    const rightWallDesc = this.RAPIER.RigidBodyDesc.fixed().setTranslation(
      this.worldWidth / 2 + 0.5,
      -this.worldHeight / 2,
      0
    );
    this.rightWallBody = this.world.createRigidBody(rightWallDesc);
    this.world.createCollider(
      this.RAPIER.ColliderDesc.cuboid(0.5, this.worldHeight + 10, 8),
      this.rightWallBody
    );

    // 4. Front & Back glass walls
    const frontWallDesc = this.RAPIER.RigidBodyDesc.fixed().setTranslation(
      0,
      -this.worldHeight / 2,
      2.0
    );
    this.frontWallBody = this.world.createRigidBody(frontWallDesc);
    this.world.createCollider(
      this.RAPIER.ColliderDesc.cuboid(this.worldWidth + 10, this.worldHeight + 10, 0.5),
      this.frontWallBody
    );

    const backWallDesc = this.RAPIER.RigidBodyDesc.fixed().setTranslation(
      0,
      -this.worldHeight / 2,
      -2.0
    );
    this.backWallBody = this.world.createRigidBody(backWallDesc);
    this.world.createCollider(
      this.RAPIER.ColliderDesc.cuboid(this.worldWidth + 10, this.worldHeight + 10, 0.5),
      this.backWallBody
    );
  }

  private seedInitialCoins(): void {
    const seedCount = Math.min(12, Math.floor(this.maxCoins * 0.7));
    const totalFallDistance = Math.max(10, Math.abs(this.floorY) - 4);

    for (let i = 0; i < seedCount; i++) {
      const staggeredY = -1.5 - (i / seedCount) * totalFallDistance;
      this.spawnCoin(staggeredY);
    }
    this.lastSpawnTime = performance.now();
  }

  private spawnCoin(customY?: number): void {
    if (!this.world || !this.instancedMesh) return;

    const freeIndex = this.coins.findIndex(c => c === null);
    if (freeIndex === -1) return;

    const spawnX = (Math.random() - 0.5) * (this.worldWidth * 0.85);
    const spawnY = customY !== undefined ? customY : 1.0;
    const spawnZ = (Math.random() - 0.5) * 1.0;
    const baseScale = 0.85 + Math.random() * 0.3;

    const bodyDesc = this.RAPIER.RigidBodyDesc.dynamic()
      .setTranslation(spawnX, spawnY, spawnZ)
      .setLinearDamping(0.25)
      .setAngularDamping(0.35);

    const body = this.world.createRigidBody(bodyDesc);

    const initialLinvel = {
      x: (Math.random() - 0.5) * 0.4,
      y: -0.6 - Math.random() * 0.6,
      z: (Math.random() - 0.5) * 0.2
    };
    body.setLinvel(initialLinvel, true);

    const initialAngvel = {
      x: (Math.random() - 0.5) * 1.5,
      y: (Math.random() - 0.5) * 1.5,
      z: (Math.random() - 0.5) * 1.5
    };
    body.setAngvel(initialAngvel, true);

    const randQuat = new this.THREE.Quaternion().setFromEuler(
      new this.THREE.Euler(
        Math.random() * Math.PI,
        Math.random() * Math.PI,
        Math.random() * Math.PI
      )
    );
    body.setRotation({ x: randQuat.x, y: randQuat.y, z: randQuat.z, w: randQuat.w }, true);

    const colliderDesc = this.RAPIER.ColliderDesc.cylinder(
      (this.COIN_HEIGHT * baseScale) / 2,
      this.COIN_RADIUS * baseScale
    )
      .setRestitution(0.3)
      .setFriction(0.65);

    const collider = this.world.createCollider(colliderDesc, body);

    this.coins[freeIndex] = {
      id: Date.now() + Math.random(),
      instanceIndex: freeIndex,
      body,
      collider,
      baseScale,
      currentScale: baseScale,
      spawnTime: performance.now(),
      landedTime: null,
      fadeStartTime: null,
      state: 'falling'
    };
  }

  private animate = (): void => {
    if (this.isDestroyed) return;

    if (typeof document !== 'undefined' && document.hidden) {
      this.animFrameId = requestAnimationFrame(this.animate);
      return;
    }

    const now = performance.now();

    if (now - this.lastSpawnTime > this.spawnInterval) {
      this.spawnCoin();
      this.lastSpawnTime = now;
      this.spawnInterval = 1200 + Math.random() * 1000;
    }

    if (this.world) {
      this.world.step();
    }

    if (this.instancedMesh) {
      let needsMatrixUpdate = false;
      let needsColorUpdate = false;

      for (let i = 0; i < this.coins.length; i++) {
        const coin = this.coins[i];
        if (!coin) continue;

        const pos = coin.body.translation();
        const rot = coin.body.rotation();
        const linvel = coin.body.linvel();

        // Safety bounds check
        if (pos.y < this.floorY - 5.0) {
          if (this.world) {
            this.world.removeRigidBody(coin.body);
          }
          this.coins[i] = null;
          this.tempMatrix.setPosition(0, 1000, 0);
          this.instancedMesh.setMatrixAt(i, this.tempMatrix);
          needsMatrixUpdate = true;
          continue;
        }

        // Detect landing near the footer floor
        if (coin.state === 'falling') {
          const distToFloor = pos.y - this.floorY;
          const speed = Math.hypot(linvel.x, linvel.y, linvel.z);

          const isResting = (distToFloor <= 4.0 && speed < 0.8) || distToFloor <= 0.6;

          if (isResting) {
            coin.state = 'resting';
            coin.landedTime = now;

            // Scoring Trigger: Check if coin settled within savings jar horizontal range
            const jarRadiusUnits = Math.min(3.8, this.worldWidth * 0.22);
            if (Math.abs(pos.x) <= jarRadiusUnits) {
              this.ngZone.run(() => {
                this.savingsJarService.recordCoinCaught(pos.x, this.PIXELS_PER_UNIT);
              });
            }
          }
        } else if (coin.state === 'resting') {
          if (coin.landedTime && now - coin.landedTime > this.REST_DURATION) {
            coin.state = 'fading';
            coin.fadeStartTime = now;
          }
        } else if (coin.state === 'fading') {
          if (coin.fadeStartTime) {
            const elapsed = now - coin.fadeStartTime;
            const progress = Math.min(1.0, elapsed / this.FADE_DURATION);
            coin.currentScale = coin.baseScale * (1.0 - progress);

            if (progress >= 1.0) {
              if (this.world) {
                this.world.removeRigidBody(coin.body);
              }
              this.coins[i] = null;
              this.tempMatrix.setPosition(0, 1000, 0);
              this.instancedMesh.setMatrixAt(i, this.tempMatrix);
              needsMatrixUpdate = true;
              continue;
            }
          }
        }

        let renderScale = coin.currentScale;
        if (this.hoveredIndex === i && coin !== this.draggedCoin) {
          renderScale *= 1.15;
        }

        this.tempPosition.set(pos.x, pos.y, pos.z);
        this.tempQuaternion.set(rot.x, rot.y, rot.z, rot.w);
        this.tempScale.set(renderScale, renderScale, renderScale);

        this.tempMatrix.compose(this.tempPosition, this.tempQuaternion, this.tempScale);
        this.instancedMesh.setMatrixAt(i, this.tempMatrix);
        needsMatrixUpdate = true;

        if (this.hoveredIndex === i) {
          this.instancedMesh.setColorAt(i, this.hoverColor);
          needsColorUpdate = true;
        } else {
          this.instancedMesh.setColorAt(i, this.defaultColor);
          needsColorUpdate = true;
        }
      }

      if (needsMatrixUpdate) {
        this.instancedMesh.instanceMatrix.needsUpdate = true;
      }
      if (needsColorUpdate && this.instancedMesh.instanceColor) {
        this.instancedMesh.instanceColor.needsUpdate = true;
      }
    }

    if (this.renderer && this.scene && this.camera) {
      this.renderer.render(this.scene, this.camera);
    }

    this.animFrameId = requestAnimationFrame(this.animate);
  };

  private setupEventListeners(): void {
    const onMouseDown: EventListener = (e: Event) => {
      const mouseEvent = e as MouseEvent;
      if (mouseEvent.button !== 0) return;

      this.updateMouseCoords(mouseEvent);

      if (this.camera && this.instancedMesh) {
        this.raycaster.setFromCamera(this.mouseNdc, this.camera);
        const intersects = this.raycaster.intersectObject(this.instancedMesh);

        if (intersects.length > 0 && intersects[0].instanceId !== undefined) {
          const hitIndex = intersects[0].instanceId;
          const hitCoin = this.coins[hitIndex];

          if (hitCoin && hitCoin.state !== 'fading') {
            this.draggedCoin = hitCoin;
            hitCoin.body.setBodyType(this.RAPIER.RigidBodyType.KinematicPositionBased, true);
            hitCoin.body.setLinvel({ x: 0, y: 0, z: 0 }, true);
            hitCoin.body.setAngvel({ x: 0, y: 0, z: 0 }, true);

            const pos = hitCoin.body.translation();
            this.lastMouseWorldX = pos.x;
            this.lastMouseWorldY = pos.y;
            this.mouseVelocityX = 0;
            this.mouseVelocityY = 0;
          }
        }
      }
    };

    const onMouseMove: EventListener = (e: Event) => {
      const mouseEvent = e as MouseEvent;
      this.updateMouseCoords(mouseEvent);

      const targetWorldX = this.mouseNdc.x * (this.worldWidth / 2);
      const targetWorldY = ((this.mouseNdc.y - 1) / 2) * this.worldHeight;

      if (this.draggedCoin) {
        this.mouseVelocityX = (targetWorldX - this.lastMouseWorldX) * 20;
        this.mouseVelocityY = (targetWorldY - this.lastMouseWorldY) * 20;
        this.lastMouseWorldX = targetWorldX;
        this.lastMouseWorldY = targetWorldY;

        this.draggedCoin.body.setNextKinematicTranslation({
          x: targetWorldX,
          y: targetWorldY,
          z: this.draggedCoin.body.translation().z
        });
      } else {
        if (this.camera && this.instancedMesh) {
          this.raycaster.setFromCamera(this.mouseNdc, this.camera);
          const intersects = this.raycaster.intersectObject(this.instancedMesh);
          if (intersects.length > 0 && intersects[0].instanceId !== undefined) {
            this.hoveredIndex = intersects[0].instanceId;
          } else {
            this.hoveredIndex = null;
          }
        }
      }
    };

    const onMouseUp: EventListener = () => {
      if (this.draggedCoin) {
        this.draggedCoin.body.setBodyType(this.RAPIER.RigidBodyType.Dynamic, true);
        const clampedVx = Math.max(-10, Math.min(10, this.mouseVelocityX));
        const clampedVy = Math.max(-10, Math.min(10, this.mouseVelocityY));
        this.draggedCoin.body.setLinvel({ x: clampedVx, y: clampedVy, z: 0 }, true);
        this.draggedCoin = null;
      }
    };

    const onResize: EventListener = () => {
      this.handleResize();
    };

    window.addEventListener('mousedown', onMouseDown);
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    window.addEventListener('resize', onResize);

    this.windowListeners.push(
      { type: 'mousedown', listener: onMouseDown },
      { type: 'mousemove', listener: onMouseMove },
      { type: 'mouseup', listener: onMouseUp },
      { type: 'resize', listener: onResize }
    );

    const canvas = this.canvasRef.nativeElement;
    const parentContainer = canvas.parentElement || document.body;
    this.resizeObserver = new ResizeObserver(() => {
      this.handleResize();
    });
    this.resizeObserver.observe(parentContainer);
  }

  private updateMouseCoords(e: MouseEvent): void {
    const canvas = this.canvasRef.nativeElement;
    const pageX = e.clientX + window.scrollX;
    const pageY = e.clientY + window.scrollY;

    const w = canvas.clientWidth || this.canvasWidth || window.innerWidth;
    const h = canvas.clientHeight || this.canvasHeight || 3500;

    this.mouseNdc.x = (pageX / w) * 2 - 1;
    this.mouseNdc.y = -(pageY / h) * 2 + 1;
  }

  private handleResize(): void {
    const canvas = this.canvasRef.nativeElement;
    if (!canvas || !this.renderer || !this.camera) return;

    const parentContainer = canvas.parentElement || document.body;
    this.updateDimensions(parentContainer);

    this.camera.left = -this.worldWidth / 2;
    this.camera.right = this.worldWidth / 2;
    this.camera.top = 0;
    this.camera.bottom = -this.worldHeight;
    this.camera.position.set(0, 0, 50);
    this.camera.lookAt(0, 0, 0);
    this.camera.updateProjectionMatrix();

    this.renderer.setSize(this.canvasWidth, this.canvasHeight, false);

    if (this.floorBody) {
      this.floorBody.setTranslation({ x: 0, y: this.floorY, z: 0 }, true);
    }
    if (this.leftWallBody) {
      this.leftWallBody.setTranslation(
        { x: -this.worldWidth / 2 - 0.5, y: -this.worldHeight / 2, z: 0 },
        true
      );
    }
    if (this.rightWallBody) {
      this.rightWallBody.setTranslation(
        { x: this.worldWidth / 2 + 0.5, y: -this.worldHeight / 2, z: 0 },
        true
      );
    }
  }

  ngOnDestroy(): void {
    this.isDestroyed = true;

    if (this.animFrameId) {
      cancelAnimationFrame(this.animFrameId);
    }

    for (const { type, listener } of this.windowListeners) {
      window.removeEventListener(type, listener);
    }
    this.windowListeners = [];

    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
    }

    if (this.world) {
      this.world.free();
      this.world = undefined;
    }

    if (this.coinGeometry) {
      this.coinGeometry.dispose();
    }
    if (this.coinMaterial) {
      this.coinMaterial.dispose();
    }
    if (this.envMap) {
      this.envMap.dispose();
    }
    if (this.pmremGenerator) {
      this.pmremGenerator.dispose();
    }
    if (this.instancedMesh) {
      this.instancedMesh.dispose();
    }
    if (this.renderer) {
      this.renderer.dispose();
      this.renderer.forceContextLoss();
    }
  }
}
