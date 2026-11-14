# Vanilla Copper Golem and Fabric API reference (MC 26.3)

Purpose: exact class/method/field names and signatures of the vanilla Copper Golem and the Fabric
API pieces the mod builds on, so mixins and API code can be written straight from this page.

Source of truth: the Loom-generated Minecraft 26.3 sources jar (`genSources`, merged "common" jar;
client-only classes such as `MenuScreens` are in the separate clientOnly jar). Fabric API ships no
sources jars here, so its signatures below come from `javap -public` on the API jars (versions in
`gradle.properties`). 26.x is unobfuscated: Mojang names, no mappings layer. Entity types live in
`net.minecraft.world.entity.EntityTypes` (constants), not `EntityType`. Identifier class is
`net.minecraft.resources.Identifier` (not ResourceLocation). Everything is verified unless marked
**unverified**.

## 1. Class hierarchy

- `net.minecraft.world.entity.animal.golem.CopperGolem extends AbstractGolem implements ContainerUser, Shearable`
- `AbstractGolem extends net.minecraft.world.entity.PathfinderMob` (`PathfinderMob extends Mob extends LivingEntity extends Entity`)
- Related: `CopperGolemAi` (static brain setup), `CopperGolemState` (enum IDLE, GETTING_ITEM,
  GETTING_NO_ITEM, DROPPING_ITEM, DROPPING_NO_ITEM), `CopperGolemOxidationLevel(s)`.
- `net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers extends Behavior<PathfinderMob>`
- Statue: `net.minecraft.world.level.block.CopperGolemStatueBlock`, `WeatheringCopperGolemStatueBlock`,
  `net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity`.
- Registered as `EntityTypes.COPPER_GOLEM` (`EntityType<CopperGolem>`, `MobCategory.MISC`).
- Attachments: every `Entity` is an `AttachmentTarget` (Fabric mixin), so `golem.getAttached(...)` works.

Key CopperGolem members (all `CopperGolem#`):
- `public static final EquipmentSlot EQUIPMENT_SLOT_ANTENNA` (= `EquipmentSlot.SADDLE`)
- `public CopperGolemState getState()` / `public void setState(CopperGolemState)`
- `public WeatheringCopper.WeatherState getWeatherState()` / `setWeatherState(WeatheringCopper.WeatherState)`
- `public Brain<CopperGolem> getBrain()` ; `protected Brain<CopperGolem> makeBrain(Brain.Packed)`
- `private long nextWeatheringTick` (-1 unset, -2 = waxed / ignore weathering)
- `public double getContainerInteractionRange()` returns 3.0
- Brain provider: `private static final Brain.Provider<CopperGolem> BRAIN_PROVIDER` = sensors
  `NEAREST_LIVING_ENTITIES`, `HURT_BY`; activities from `CopperGolemAi.getActivities()`.
- Ctor sets `MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS` to random 60..99.
- Carried item = **main hand** (`getMainHandItem()`); golem is "picking up" iff main hand is empty.

## 2. Interaction: `mobInteract`

Dispatch chain on a right-click (`Entity#interact` is called by the player-interact packet handler):
1. `net.minecraft.world.entity.Mob#interact(Player, InteractionHand, Vec3): InteractionResult` (public)
   - not alive -> PASS
   - `checkAndHandleImportantInteractions`: name tag (`itemStack.interactLivingEntity`), spawn egg
   - `Entity#interact(Player, InteractionHand, Vec3)` (super): shift+lead attach to this entity;
     **shears removing leash connections / equipment**; **already leashed to this player ->
     unleash (any hand, incl. empty)**; Lead item -> leash. Returns non-PASS here => `mobInteract` is never reached.
   - then `mobInteract`; if the result `consumesAction()` fires `GameEvent.ENTITY_INTERACT`.
2. `public InteractionResult net.minecraft.world.entity.animal.golem.CopperGolem#mobInteract(Player player, InteractionHand hand)`
   (overrides `protected InteractionResult Mob#mobInteract(Player, InteractionHand)`; CopperGolem widens it to public). Branches in order:
   1. **Empty hand** (`player.getItemInHand(hand).isEmpty()`) and golem main hand non-empty ->
      `BehaviorUtils.throwItem(this, equipped, player.position())`, main hand cleared, return `InteractionResult.SUCCESS`.
      (Empty hand + empty golem hand falls through to the rest, ending in `super.mobInteract` = PASS.)
   2. **Shears** and `readyForShearing()` (antenna slot item in `ItemTags.SHEARABLE_FROM_COPPER_GOLEM`):
      server: `shear(...)`, `GameEvent.SHEAR`, `hurtAndBreak`; return `SUCCESS` (both sides).
   3. Client side -> `PASS`.
   4. **Honeycomb** and `nextWeatheringTick != -2` -> wax (`levelEvent 3003`, sets -2, `usePlayerItem`), `SUCCESS_SERVER`.
   5. **Axe** (`ItemTags.AXES`) and waxed (-2) -> unwax (`levelEvent 3004`), `SUCCESS_SERVER`.
   6. **Axe** and weather state != UNAFFECTED -> scrape one oxidation stage back (`levelEvent 3005`), `SUCCESS_SERVER`.
   7. `super.mobInteract(player, hand)` -> `PASS`.
   - Name tag, spawn egg and lead are NOT in `mobInteract` (handled in the chain above).
   - `InteractionResult` constants used: `SUCCESS`, `SUCCESS_SERVER`, `PASS`; `consumesAction()`.
3. Menu-open helper: `net.minecraft.world.entity.player.Player#openMenu(MenuProvider): OptionalInt`
   (`ServerPlayer#openMenu` overrides it; `Player` base returns `OptionalInt.empty()`).

## 3. AI step and brain tick

- `net.minecraft.world.entity.Mob#serverAiStep(): void` is `protected final` (cannot be overridden or
  mixed with an override; it calls goal selectors, navigation, then `customServerAiStep`, then controls).
- `protected void CopperGolem#customServerAiStep(ServerLevel level)` (overrides empty
  `Mob#customServerAiStep(ServerLevel)`; `PathfinderMob` does not override it). Body in order:
  `getBrain().tick(level, this)` (profiler "copperGolemBrain"), `CopperGolemAi.updateActivity(this)`
  (profiler "copperGolemActivityUpdate"), `super.customServerAiStep(level)`.
- `public void net.minecraft.world.entity.ai.Brain#tick(ServerLevel level, E body)`: erased descriptor
  `tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V` (`E extends LivingEntity`).
  Runs `forgetOutdatedMemories`, `tickSensors`, `startEachNonRunningBehavior`, `tickEachRunningBehavior`.
- `public void CopperGolem#tick()`: server side calls private `updateWeathering(ServerLevel, RandomSource, long)`.
- Brain memories persist through the entity save (memory types have codecs), including
  `VISITED_BLOCK_POSITIONS` and `UNREACHABLE_TRANSPORT_BLOCK_POSITIONS` (`Set<GlobalPos>`, codec via list).

## 4. `CopperGolemAi`

`net.minecraft.world.entity.animal.golem.CopperGolemAi` (class `public`, all members static):
- `protected static List<ActivityData<CopperGolem>> getActivities()` -> `[initCoreActivity(), initIdleActivity()]`
- `public static void updateActivity(CopperGolem body)` -> `body.getBrain().setActiveActivityToFirstValid(ImmutableList.of(Activity.IDLE))`
- Constants (private static final): `SPEED_MULTIPLIER_WHEN_PANICKING = 1.5F`, `SPEED_MULTIPLIER_WHEN_IDLING = 1.0F`,
  `TRANSPORT_ITEM_HORIZONTAL_SEARCH_RADIUS = 32`, `TRANSPORT_ITEM_VERTICAL_SEARCH_RADIUS = 8`,
  `TICK_TO_START_ON_REACHED_INTERACTION = 1`, `TICK_TO_PLAY_ON_REACHED_SOUND = 9`.
- `TRANSPORT_ITEM_SOURCE_BLOCK = state.is(BlockTags.COPPER_CHESTS)`;
  `TRANSPORT_ITEM_DESTINATION_BLOCK = state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)`.
- Core activity (`Activity.CORE`, priority 0): `AnimalPanic(1.5F)`, `LookAtTargetSink(45, 90)`, `MoveToTargetSink`,
  `InteractWithDoor.create()`, `CountDownCooldownTicks(GAZE_COOLDOWN_TICKS)`,
  `CountDownCooldownTicks(TRANSPORT_ITEMS_COOLDOWN_TICKS)`.
- Idle activity (`Activity.IDLE`), priorities:
  - 0: `new TransportItemsBetweenContainers(1.0F, SOURCE, DESTINATION, 32, 8, getTargetReachedInteractions(), onTravelling(), shouldQueueForTarget())`
  - 1: `SetEntityLookTargetSometimes.create(EntityTypes.PLAYER, 6.0F, UniformInt.of(40, 80))`
  - 2: `RunOne` (needs `WALK_TARGET` absent and `TRANSPORT_ITEMS_COOLDOWN_TICKS` present): `RandomStroll.stroll(1.0F, 2, 2)` / `DoNothing(30, 60)`, weight 1 each.
- Interactions map `ContainerInteractionState` -> `OnTargetReachedInteraction` via private
  `onReachedTargetInteraction(CopperGolemState, SoundEvent)`: tick 1 `container.startOpen(golem)` +
  `setOpenedChestPos` + `setState`; tick 9 sound; tick 60 `stopOpen` + `clearOpenedChestPos`.
- `shouldQueueForTarget()`: `ChestBlockEntity` target with any `getEntitiesWithContainerOpen()`.
- Only ONE brain behavior for the transport; a profession that replaces it can simply stop the brain
  from running (see section 11) or swap the activity list (`getActivities()` is `protected static`).

## 5. `TransportItemsBetweenContainers`

`net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers extends Behavior<PathfinderMob>`
- ctor `public TransportItemsBetweenContainers(float speedModifier, Predicate<BlockState> sourceBlockType, Predicate<BlockState> destinationBlockType, int horizontalSearchDistance, int verticalSearchDistance, Map<ContainerInteractionState, OnTargetReachedInteraction> onTargetInteractionActions, Consumer<PathfinderMob> onStartTravelling, Predicate<TransportItemTarget> shouldQueueForTarget)`
- Required memory conditions: `TRANSPORT_ITEMS_COOLDOWN_TICKS` VALUE_ABSENT, `IS_PANICKING` VALUE_ABSENT; visited/unreachable REGISTERED.
- Constants (all `private static final` unless noted):
  `TARGET_INTERACTION_TIME = 60` (`public`), `VISITED_POSITIONS_MEMORY_TIME = 6000`, `TRANSPORTED_ITEM_MAX_STACK_SIZE = 16`,
  `MAX_VISITED_POSITIONS = 10`, `MAX_UNREACHABLE_POSITIONS = 50`, `IDLE_COOLDOWN = 140`,
  `PASSENGER_MOB_TARGET_SEARCH_DISTANCE = 1`, close-enough distances 3.0 / 0.5 / 1.0 / 2.0.
- Search area: `new AABB(mob.blockPosition()).inflate(32, 8, 32)` = 65x17x65 (1/1 when the golem is a passenger).
  Candidates = `ChestBlockEntity` in loaded chunks, nearest first, not visited/unreachable, not `isLocked()`,
  not blocked (`ChestBlock.isChestBlockedAt`). Double chests count as one (`getConnectedTargets`).
- **Pickup**: `private static ItemStack pickupItemFromContainer(Container)`: first non-empty slot,
  `removeItem(slot, Math.min(count, 16))` (literal 16, constant is inlined). Needs `!container.isEmpty()`.
  `pickUpItems` sets MAINHAND and `setGuaranteedDrop(MAINHAND)` (drop chance > 1 => "preserved").
- **Place**: allowed iff `container.isEmpty() || hasItemMatchingHandItem` (`ItemStack.isSameItem` over any slot);
  `addItemsToContainer` fills first empty slot or merges same item+components. Leftover stays in hand.
- Cooldown on failure: `enterCooldownAfterNoMatchingTargetFound(PathfinderMob)` sets
  `TRANSPORT_ITEMS_COOLDOWN_TICKS = 140`, erases visited + unreachable memories. Success erases both too.

### Visited-chest limit (the "9 remembered / 10 misses" value)
- Field: `MemoryModuleType.VISITED_BLOCK_POSITIONS` (`Set<GlobalPos>`), expiry 6000 ticks.
- The limit is `protected void TransportItemsBetweenContainers#setVisitedBlockPos(PathfinderMob body, Level level, BlockPos target)`:
  copies the set, adds the new `GlobalPos`, `if (visitedPositions.size() > 10) enterCooldownAfterNoMatchingTargetFound(body)`
  else `setMemoryWithExpiry(VISITED_BLOCK_POSITIONS, visitedPositions, 6000L)`.
- **The `10` in `setVisitedBlockPos` is a bare literal** (`MAX_VISITED_POSITIONS` is declared but never
  read: javac inlines it). So effective rule: the 11th distinct chest tried triggers the 140-tick wander
  cooldown; i.e. a golem tolerates 10 remembered chests.
- Only call site of `setVisitedBlockPos`: `private boolean updateInvalidTarget(ServerLevel, PathfinderMob)` (when a new target is chosen).
- Other users of the visited set: `getVisitedPositions(PathfinderMob)` (reads memory),
  `isPositionAlreadyVisited(...)`, `getTransportTarget(...)` (skip visited), `markVisitedBlockPosAsUnreachable(...)`
  (removes from visited, adds to unreachable; unreachable limit literal `50`, same pattern).
- Behavior instance state (`target`, `state`, `ticksSinceReachingTarget`) is per brain => one instance per golem.

## 6. Death drops and statue conversion

Death flow: `LivingEntity#die(DamageSource)` -> `protected void LivingEntity#dropAllDeathLoot(ServerLevel, DamageSource)`:
1. if `shouldDropLoot(level)`: `dropFromLootTable(...)` then `protected void dropCustomDeathLoot(ServerLevel, DamageSource, boolean killedByPlayer)`
   (`Mob` overrides: drops equipment by chance, preserved ones always)
2. **always**: `protected void LivingEntity#dropEquipment(ServerLevel)` (empty in base)
3. `dropExperience`.
- `protected void CopperGolem#dropEquipment(ServerLevel level)` overrides: `super.dropEquipment(level)` then
  `dropPreservedEquipment(level)`. Because the carried item has `setGuaranteedDrop`, it drops here.
  **Best hook for dumping mod-stored items on death**: `dropEquipment` (runs regardless of the `shouldDropLoot` gate, e.g. gamerule off).
- `CopperGolem#dropPreservedEquipment(ServerLevel)` is the call site of the no-arg drop. Mixin `INVOKE` targets must name
  `CopperGolem` as owner (`Lnet/minecraft/world/entity/animal/golem/CopperGolem;dropPreservedEquipment(Lnet/minecraft/server/level/ServerLevel;)V`),
  not `Mob`: a `Mob` owner made the first build crash. `Mob` also has the overload
  `Set<EquipmentSlot> Mob#dropPreservedEquipment(ServerLevel, Predicate<ItemStack>)`.
- `public void Entity#spawnAtLocation(ServerLevel, ItemStack)`; `CopperGolem` also uses `spawnAtLocation(level, stack, 1.5F)` in `shear`.

Statue conversion (oxidised golem -> statue block), all in `CopperGolem`:
- `private void updateWeathering(ServerLevel, RandomSource, long gameTime)`: if `nextWeatheringTick != -2` (not waxed)
  and state is OXIDIZED and `canTurnToStatue(level)` then `turnToStatue(level)`.
- `private boolean canTurnToStatue(Level)`: block at `blockPosition()` is air and `nextFloat() <= 0.0058F`.
- `private void turnToStatue(ServerLevel level)`: `setBlockAndUpdate(pos, Blocks.COPPER_GOLEM_STATUE.weathering().oxidized().defaultBlockState()` with random `POSE`, `FACING`);
  then if `level.getBlockEntity(pos) instanceof CopperGolemStatueBlockEntity be`:
  `be.createStatue(this)` (stores only custom name), **`this.dropPreservedEquipment(level)`** (the held item), `this.discard()`,
  `playSound(COPPER_GOLEM_BECOME_STATUE)`, drop or remove the leash.
- Only the *held item* (main hand) and other preserved slots drop; the statue keeps nothing else, and no
  attachment data survives (`discard`, not `die`, so `dropEquipment`/death hooks do NOT run).
- Reverse: `WeatheringCopperGolemStatueBlock#useItemOn(ItemStack, BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)`
  with an axe on an UNAFFECTED statue -> `CopperGolemStatueBlockEntity#removeStatue(BlockState): CopperGolem` (fresh golem, only custom name restored).
- Other golem creation: `CarvedPumpkinBlock` (build pattern) via `EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.TRIGGERED)`.

## 7. Fabric API: data attachments (fabric-data-attachment-api-v1)

Package `net.fabricmc.fabric.api.attachment.v1`.
- `AttachmentRegistry.createPersistent(Identifier, Codec<A>): AttachmentType<A>` (static, generic `<A>`)
- `AttachmentRegistry.create(Identifier, Consumer<AttachmentRegistry.Builder<A>>): AttachmentType<A>`; also `create(Identifier)`, `createDefaulted(Identifier, Supplier<A>)`, `builder()`
- `AttachmentRegistry.Builder<A>`: `persistent(Codec<A>)`, `copyOnDeath()`, `initializer(Supplier<A>)`,
  `syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A>, AttachmentSyncPredicate)`, `syncWith(..., int)`,
  `buildAndRegister(Identifier): AttachmentType<A>`
- `AttachmentSyncPredicate`: `all()`, `targetOnly()`, `allButTarget()` (a `BiPredicate<AttachmentTarget, ServerPlayer>`)
- `AttachmentTarget` (default methods): `getAttached(type): A` (nullable), `getAttachedOrThrow`, `getAttachedOrCreate(type)`,
  `getAttachedOrCreate(type, Supplier)`, `getAttachedOrSet(type, A)`, `getAttachedOrElse(type, A)`, `getAttachedOrGet(type, Supplier)`,
  `setAttached(type, A): A` (returns previous), `hasAttached(type): boolean`, `removeAttached(type): A`,
  `modifyAttached(type, UnaryOperator<A>): A`, `onAttachedSet(type): Event<OnAttachedSet<A>>`
- `AttachmentType<A>`: `identifier()`, `persistenceCodec()`, `isPersistent()`, `initializer()`, `isSynced()`, `copyOnDeath()`

```java
static final AttachmentType<GolemData> DATA = AttachmentRegistry.create(
    Identifier.fromNamespaceAndPath("coppergolemsrevamped", "golem"),
    b -> b.persistent(GolemData.CODEC).initializer(GolemData::new).syncWith(GolemData.STREAM_CODEC, AttachmentSyncPredicate.all()));
GolemData d = golem.getAttachedOrCreate(DATA);   // mutate, then golem.setAttached(DATA, d) so sync/persist see the change
```
Usage note: prefer immutable records and `setAttached`/`modifyAttached` so persistence/sync see the change (**unverified** whether in-place mutation of a `getAttachedOrCreate` value is persisted).

## 8. Fabric API: menus (fabric-menu-api-v1)

Package `net.fabricmc.fabric.api.menu.v1` ("ScreenHandler" is `net.minecraft.world.inventory.AbstractContainerMenu`).
- `ExtendedMenuType<T extends AbstractContainerMenu, D> extends MenuType<T>`:
  ctor `(ExtendedMenuType.ExtendedFactory<T, D>, StreamCodec<? super RegistryFriendlyByteBuf, D>)`;
  `T create(int, Inventory, D)`; `StreamCodec<? super RegistryFriendlyByteBuf, D> getStreamCodec()`.
  `ExtendedFactory<T, D>`: `T create(int containerId, Inventory, D data)`.
- `ExtendedMenuProvider<D> extends net.minecraft.world.MenuProvider`: `D getScreenOpeningData(ServerPlayer)`.
  `MenuProvider`: `Component getDisplayName()` + `MenuConstructor#createMenu(int containerId, Inventory, Player): AbstractContainerMenu`.
- `FabricMenuProvider#shouldCloseCurrentScreen(): boolean` (default).
- Registration: `Registry.register(BuiltInRegistries.MENU, Identifier, menuType)` (`BuiltInRegistries.MENU` is `Registry<MenuType<?>>`).
- Client screen: `net.minecraft.client.gui.screens.MenuScreens#register(MenuType<? extends M>, MenuScreens.ScreenConstructor<M, U>)`
  where `U extends Screen & MenuAccess<M>`; `ScreenConstructor#create(M menu, Inventory, Component title)` (clientOnly jar).
- `AbstractContainerMenu` abstract methods: `ItemStack quickMoveStack(Player, int)`, `boolean stillValid(Player)`;
  ctor `protected AbstractContainerMenu(@Nullable MenuType<?>, int containerId)`.
```java
public static final ExtendedMenuType<GolemMenu, Integer> TYPE =
    new ExtendedMenuType<>((id, inv, entityId) -> new GolemMenu(id, inv, entityId), ByteBufCodecs.VAR_INT.cast());  // StreamCodec#cast() exists; VAR_INT is StreamCodec<ByteBuf,Integer>
// in the interact hook (server): serverPlayer.openMenu(new ExtendedMenuProvider<Integer>() { getScreenOpeningData -> golem.getId(); getDisplayName; createMenu })
```

## 9. Fabric API: networking v1

- `CustomPacketPayload#type(): CustomPacketPayload.Type<? extends CustomPacketPayload>`; `record CustomPacketPayload.Type<T>(Identifier id)`;
  static `CustomPacketPayload.createType(String id)` uses the `minecraft` namespace, so build `new CustomPacketPayload.Type<>(Identifier)` for mod ids;
  static `<B extends ByteBuf, T> StreamCodec<B,T> CustomPacketPayload.codec(StreamMemberEncoder<B,T>, StreamDecoder<B,T>)`.
- `PayloadTypeRegistry<B>`: statics `serverboundPlay()` / `clientboundPlay()` (`PayloadTypeRegistry<RegistryFriendlyByteBuf>`),
  `serverboundConfiguration()` / `clientboundConfiguration()` (`<FriendlyByteBuf>`); instance
  `<T extends CustomPacketPayload> TypeAndCodec<? super B, T> register(Type<T>, StreamCodec<? super B, T>)`; `registerLarge(..., int)` variants.
  Register on BOTH sides (common initializer) before use.
- `ServerPlayNetworking`: `static <T> boolean registerGlobalReceiver(CustomPacketPayload.Type<T>, PlayPayloadHandler<T>)`,
  `static void send(ServerPlayer, CustomPacketPayload)`, `canSend(ServerPlayer, Type<?>)`;
  `PlayPayloadHandler<T>#receive(T payload, ServerPlayNetworking.Context ctx)`; `Context`: `server()`, `player(): ServerPlayer`, `responseSender()`.
  Handlers run on the server thread (Context-based API, no manual `execute`; **unverified** threading claim).
- `net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking`: `registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)`,
  `static void send(CustomPacketPayload)`, `Context`: `client(): Minecraft`, `player(): LocalPlayer`.
```java
record SetProfession(int golemId, int profession) implements CustomPacketPayload {
  static final Type<SetProfession> TYPE = new Type<>(Identifier.fromNamespaceAndPath("coppergolemsrevamped","set_profession"));
  static final StreamCodec<RegistryFriendlyByteBuf, SetProfession> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, SetProfession::golemId, ByteBufCodecs.VAR_INT, SetProfession::profession, SetProfession::new); // StreamCodec.composite has overloads up to many fields
  public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
PayloadTypeRegistry.serverboundPlay().register(SetProfession.TYPE, SetProfession.CODEC);
ServerPlayNetworking.registerGlobalReceiver(SetProfession.TYPE, (p, ctx) -> { /* ctx.player() */ });
```

## 10. Fabric API: GameTest

`net.fabricmc.fabric.api.gametest.v1.GameTest` annotation (method takes `GameTestHelper`; see `src/gametest/.../TransporterTest`). Attributes and defaults:
`environment = "minecraft:default"`, `dimension = "minecraft:overworld"`, `structure = "fabric-gametest-api-v1:empty"` (empty structure),
`maxTicks = 20`, `setupTicks = 0`, `required = true`, `rotation = Rotation.NONE`, `manualOnly = false`,
`maxAttempts = 1`, `requiredSuccesses = 1`, `skyAccess = false`, `padding = 1`.
`CustomTestMethodInvoker#invokeTestMethod(GameTestHelper, Method)` for custom invocation.

`net.minecraft.gametest.framework.GameTestHelper` (coordinates are relative to the test structure):
- `ServerLevel getLevel()`, `BlockPos absolutePos(BlockPos)`, `BlockPos relativePos(BlockPos)`, `long getTick()`
- spawn: `<E extends Entity> E spawn(EntityType<E>, BlockPos)` (also `Vec3`, `int x,y,z`, `float x,y,z`, `(type, pos, EntitySpawnReason)`);
  `<E extends Mob> E spawnWithNoFreeWill(EntityType<E>, BlockPos)` (no AI: use for static golems; NOT for brain tests);
  `spawnMob(EntityType<E>, BlockPos): GameTestMobBuilder<E>`; `findEntities(EntityType<E>, Vec3 pos, double distance): List<E>`
- blocks: `void setBlock(BlockPos, Block)` / `(BlockPos, BlockState)`; `placeBlock(BlockPos, Block, Direction)`; `BlockState getBlockState(BlockPos)`;
  `<T extends BlockEntity> T getBlockEntity(BlockPos, Class<T>)`; `useBlock(BlockPos)` / `(BlockPos, Player)`
- items into containers: no helper; use `helper.getBlockEntity(pos, ChestBlockEntity.class).setItem(slot, stack)`.
- assertions: `assertContainerEmpty(BlockPos)`, `assertContainerContains(BlockPos, Item)`, `assertContainerContainsSingle(BlockPos, Item)`,
  `assertEntityPresent(EntityType<?>, BlockPos)`, `assertEntityIsHolding(BlockPos, EntityType<E>, Item)`, `assertItemEntityPresent(Item)`,
  `assertBlockPresent(Block, BlockPos)`, `assertTrue(boolean, String)`, `assertValueEqual(N, N, String)`
- completion: `succeed()`, `succeedWhen(Runnable)` (polled each tick until no exception, then succeeds; only one final clause per test),
  `succeedIf(Runnable)`, `succeedOnTickWhen(int, Runnable)`, `fail(String)`, `runAfterDelay(long, Runnable)`, `runAtTickTime(long, Runnable)`, `startSequence(): GameTestSequence`
- players: `makeMockPlayer(GameType): Player`, `makeMockServerPlayerInLevel(): ServerPlayer`
```java
@GameTest(maxTicks = 400)
public void sortsItems(GameTestHelper h) {
  h.setBlock(new BlockPos(1,1,1), Blocks.COPPER_CHEST.weathering().unaffected()); h.setBlock(new BlockPos(4,1,1), Blocks.CHEST);
  h.getBlockEntity(new BlockPos(1,1,1), ChestBlockEntity.class).setItem(0, new ItemStack(Items.APPLE, 4));
  h.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(2,1,1));
  h.succeedWhen(() -> h.assertContainerContains(new BlockPos(4,1,1), Items.APPLE));
}
```
`Blocks.COPPER_CHEST` and `Blocks.COPPER_GOLEM_STATUE` are `WeatheringCopperCollection<Block>` records (not `Block`): use `.weathering().unaffected()` (`ByState` has `unaffected/exposed/weathered/oxidized`) or `.waxed()`. Tag `BlockTags.COPPER_CHESTS` covers all copper chests.

## 11. Mixin targets (summary)

| Goal | Target | Injection |
|---|---|---|
| Empty-hand opens menu | `CopperGolem#mobInteract(Player, InteractionHand)` (public, `Lnet/minecraft/world/InteractionResult;`) | `@Inject(at=@At("HEAD"), cancellable=true)`: if `player.getItemInHand(hand).isEmpty()`: server -> `((ServerPlayer)player).openMenu(...)`, return `SUCCESS_SERVER` (client: `SUCCESS`); else do nothing. Leashed-to-self and name tag/lead/spawn egg are decided earlier in `Mob#interact` and unaffected; shears/honeycomb/axes continue to vanilla. Note vanilla empty-hand throws held item: the mod menu must replace or expose that. |
| Gate the vanilla transport brain | `CopperGolem#customServerAiStep(ServerLevel)` | `@WrapOperation` on the `Brain#tick(ServerLevel, LivingEntity)` call (skip when the golem has a non-Chest-Sorter profession; run profession tick instead), (an `@Inject(HEAD, cancellable)` cannot skip only the brain tick and still run `super.customServerAiStep`; prefer wrapping). `Mob#serverAiStep` is final, do not target it. Alternative: `@Inject` into `CopperGolem#tick()` for profession ticking (runs also when AI is off). |
| Chest limit override | `TransportItemsBetweenContainers#setVisitedBlockPos(PathfinderMob, Level, BlockPos)` (protected) | `@ModifyConstant(method="setVisitedBlockPos", constant=@Constant(intValue=10))` (or MixinExtras `@ModifyExpressionValue` on the `Set#size()` result). Read the limit from the `body` (`body.getAttached(DATA)`) via a `@Redirect`/wrap of the size comparison since the constant has no golem access. The same literal `10` appears nowhere else in that method; `50` in `markVisitedBlockPosAsUnreachable` is separate. |
| Death drops | `CopperGolem#dropEquipment(ServerLevel)` (protected) | `@Inject(at=@At("TAIL"))`: spawn stored items with `spawnAtLocation(level, stack)`. Runs on every `die()` path. |
| Statue conversion drops | `CopperGolem#turnToStatue(ServerLevel)` (private) | `@Inject(method="turnToStatue", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/animal/golem/CopperGolem;dropPreservedEquipment(Lnet/minecraft/server/level/ServerLevel;)V"))` so it only fires after the block entity was created; drop stored items there. `discard()` follows, `die` hooks are skipped. |

Verify each descriptor against the compiled class (`javap -p`) when writing the mixin; the targets above
come from decompiled source (names exact, synthetic details unverified).

## 12. Behavioural summary and corrections to earlier assumptions

- Visited-chest limit is **10** (11th triggers wander), not 9; it is a literal in `setVisitedBlockPos`.
- Search radius is 32 horizontal / 8 vertical (65x17x65 box); pickup count is up to 16 of the **first** non-empty slot.
- Wander cooldown after failure is 140 ticks (idle `RandomStroll` runs during it).
- Place rule: destination chest must be empty OR contain a slot with the same item type (`isSameItem`, ignoring components).
