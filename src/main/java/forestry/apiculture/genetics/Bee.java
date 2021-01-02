/*******************************************************************************
 * Copyright (c) 2011-2014 SirSengir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Various Contributors including, but not limited to:
 * SirSengir (original work), CovertJaguar, Player, Binnie, MysteriousAges
 ******************************************************************************/
package forestry.apiculture.genetics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.item.EnumRarity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import com.mojang.authlib.GameProfile;

import forestry.api.apiculture.BeeManager;
import forestry.api.apiculture.EnumBeeChromosome;
import forestry.api.apiculture.FlowerManager;
import forestry.api.apiculture.IAlleleBeeEffect;
import forestry.api.apiculture.IAlleleBeeSpecies;
import forestry.api.apiculture.IApiaristTracker;
import forestry.api.apiculture.IBee;
import forestry.api.apiculture.IBeeGenome;
import forestry.api.apiculture.IBeeHousing;
import forestry.api.apiculture.IBeeModifier;
import forestry.api.apiculture.IBeeMutation;
import forestry.api.apiculture.IBeekeepingMode;
import forestry.api.core.BiomeHelper;
import forestry.api.core.EnumHumidity;
import forestry.api.core.EnumTemperature;
import forestry.api.core.IErrorState;
import forestry.api.genetics.AlleleManager;
import forestry.api.genetics.EnumTolerance;
import forestry.api.genetics.IAllele;
import forestry.api.genetics.IAlleleInteger;
import forestry.api.genetics.IAlleleTolerance;
import forestry.api.genetics.IChromosome;
import forestry.api.genetics.IEffectData;
import forestry.api.genetics.IFlowerProvider;
import forestry.api.genetics.IIndividual;
import forestry.api.genetics.IMutation;
import forestry.api.genetics.IPollinatable;
import forestry.api.lepidopterology.EnumButterflyChromosome;
import forestry.apiculture.genetics.alleles.AlleleEffectNone;
import forestry.arboriculture.genetics.pollination.FakePollinatable;
import forestry.arboriculture.genetics.pollination.ICheckPollinatable;
import forestry.core.config.Config;
import forestry.core.config.Constants;
import forestry.core.errors.EnumErrorCode;
import forestry.core.genetics.Chromosome;
import forestry.core.genetics.GenericRatings;
import forestry.core.genetics.IndividualLiving;
import forestry.core.genetics.alleles.AlleleBoolean;
import forestry.core.utils.GeneticsUtil;
import forestry.core.utils.Log;
import forestry.core.utils.StringUtil;
import forestry.core.utils.vect.Vect;

public class Bee extends IndividualLiving implements IBee {

	private int generation;
	private boolean isNatural = true;

	private IBeeGenome genome;
	private IBeeGenome mate;

	/* CONSTRUCTOR */
	public Bee(NBTTagCompound nbttagcompound) {
		readFromNBT(nbttagcompound);
	}

	public Bee(IBeeGenome genome, IBee mate) {
		this(genome);
		this.mate = mate.getGenome();
	}

	public Bee(IBeeGenome genome) {
		this(genome, true, 0);
	}

	private Bee(IBeeGenome genome, boolean isNatural, int generation) {
		super(genome.getLifespan());
		this.genome = genome;
		this.isNatural = isNatural;
		this.generation = generation;
	}

	/* SAVING & LOADING */
	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {

		if (nbttagcompound == null) {
			genome = BeeDefinition.FOREST.getGenome();
			return;
		}

		super.readFromNBT(nbttagcompound);

		if (nbttagcompound.hasKey("NA")) {
			isNatural = nbttagcompound.getBoolean("NA");
		}

		if (nbttagcompound.hasKey("GEN")) {
			generation = nbttagcompound.getInteger("GEN");
		}

		if (nbttagcompound.hasKey("Genome")) {
			genome = BeeGenome.fromNBT(nbttagcompound.getCompoundTag("Genome"));
		} else {
			genome = BeeDefinition.FOREST.getGenome();
		}

		if (nbttagcompound.hasKey("Mate")) {
			mate = BeeGenome.fromNBT(nbttagcompound.getCompoundTag("Mate"));
		}

	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {

		super.writeToNBT(nbttagcompound);

		if (!isNatural) {
			nbttagcompound.setBoolean("NA", false);
		}

		if (generation > 0) {
			nbttagcompound.setInteger("GEN", generation);
		}
	}

	public void setIsNatural(boolean flag) {
		this.isNatural = flag;
	}

	public boolean isNatural() {
		return this.isNatural;
	}

	public int getGeneration() {
		return generation;
	}

	@Override
	public void mate(IIndividual individual) {
		if (!(individual instanceof IBee)) {
			return;
		}

		IBee drone = (IBee) individual;
		mate = drone.getGenome();
	}

	/* EFFECTS */
	@Override
	public IEffectData[] doEffect(IEffectData[] storedData, IBeeHousing housing) {
		IAlleleBeeEffect effect = genome.getEffect();

		if (effect == null) {
			return null;
		}

		storedData[0] = doEffect(effect, storedData[0], housing);

		// Return here if the primary can already not be combined
		if (!effect.isCombinable()) {
			return storedData;
		}

		IAlleleBeeEffect secondary = (IAlleleBeeEffect) genome.getInactiveAllele(EnumBeeChromosome.EFFECT);
		if (!secondary.isCombinable()) {
			return storedData;
		}

		storedData[1] = doEffect(secondary, storedData[1], housing);

		return storedData;
	}

	private IEffectData doEffect(IAlleleBeeEffect effect, IEffectData storedData, IBeeHousing housing) {
		storedData = effect.validateStorage(storedData);
		return effect.doEffect(genome, storedData, housing);
	}

	@Override
	public IEffectData[] doFX(IEffectData[] storedData, IBeeHousing housing) {
		IAlleleBeeEffect effect = genome.getEffect();

		if (effect == null) {
			return null;
		}

		storedData[0] = doFX(effect, storedData[0], housing);

		// Return here if the primary can already not be combined
		if (!effect.isCombinable()) {
			return storedData;
		}

		IAlleleBeeEffect secondary = (IAlleleBeeEffect) genome.getInactiveAllele(EnumBeeChromosome.EFFECT);
		if (!secondary.isCombinable()) {
			return storedData;
		}

		storedData[1] = doFX(secondary, storedData[1], housing);

		return storedData;
	}

	private IEffectData doFX(IAlleleBeeEffect effect, IEffectData storedData, IBeeHousing housing) {
		return effect.doFX(genome, storedData, housing);
	}

	// / INFORMATION
	@Override
	public IBeeGenome getGenome() {
		return genome;
	}

	@Override
	public IBeeGenome getMate() {
		return mate;
	}

	@Override
	public IBee copy() {
		NBTTagCompound nbttagcompound = new NBTTagCompound();
		this.writeToNBT(nbttagcompound);
		return new Bee(nbttagcompound);

	}

	@Override
	public boolean canSpawn() {
		return mate != null;
	}

	@Override
	public Set<IErrorState> getCanWork(IBeeHousing housing) {
		World world = housing.getWorld();
		BiomeGenBase biome = housing.getBiome();

		Set<IErrorState> errorStates = new HashSet<>();

		IBeeModifier beeModifier = BeeManager.beeRoot.createBeeHousingModifier(housing);

		// / Rain needs tolerant flyers
		if (world.isRaining() && BiomeHelper.canRainOrSnow(biome) && !canFlyInRain(beeModifier)) {
			errorStates.add(EnumErrorCode.IS_RAINING);
		}

		// / Night or darkness requires nocturnal species
		if (world.isDaytime()) {
			if (!canWorkDuringDay()) {
				errorStates.add(EnumErrorCode.NOT_NIGHT);
			}
		} else {
			if (!canWorkAtNight(beeModifier)) {
				errorStates.add(EnumErrorCode.NOT_DAY);
			}
		}

		if (housing.getBlockLightValue() > Constants.APIARY_MIN_LEVEL_LIGHT) {
			if (!canWorkDuringDay()) {
				errorStates.add(EnumErrorCode.NOT_GLOOMY);
			}
		} else {
			if (!canWorkAtNight(beeModifier)) {
				errorStates.add(EnumErrorCode.NOT_BRIGHT);
			}
		}

		// / Check for the sky, except if in hell
		if (!world.provider.hasNoSky) {
			if (!housing.canBlockSeeTheSky() && !canWorkUnderground(beeModifier)) {
				errorStates.add(EnumErrorCode.NO_SKY);
			}
		}

		// / And finally climate check
		IAlleleBeeSpecies species = genome.getPrimary();
		{
			EnumTemperature actualTemperature = housing.getTemperature();
			EnumTemperature beeBaseTemperature = species.getTemperature();
			EnumTolerance beeToleranceTemperature = genome.getToleranceTemp();

			if (!AlleleManager.climateHelper.isWithinLimits(actualTemperature, beeBaseTemperature, beeToleranceTemperature)) {
				if (beeBaseTemperature.ordinal() > actualTemperature.ordinal()) {
					errorStates.add(EnumErrorCode.TOO_COLD);
				} else {
					errorStates.add(EnumErrorCode.TOO_HOT);
				}
			}
		}

		{
			EnumHumidity actualHumidity = housing.getHumidity();
			EnumHumidity beeBaseHumidity = species.getHumidity();
			EnumTolerance beeToleranceHumidity = genome.getToleranceHumid();

			if (!AlleleManager.climateHelper.isWithinLimits(actualHumidity, beeBaseHumidity, beeToleranceHumidity)) {
				if (beeBaseHumidity.ordinal() > actualHumidity.ordinal()) {
					errorStates.add(EnumErrorCode.TOO_ARID);
				} else {
					errorStates.add(EnumErrorCode.TOO_HUMID);
				}
			}
		}

		return errorStates;
	}

	private boolean canWorkAtNight(IBeeModifier beeModifier) {
		return genome.getPrimary().isNocturnal() || genome.getNocturnal() || beeModifier.isSelfLighted();
	}

	private boolean canWorkDuringDay() {
		return !genome.getPrimary().isNocturnal() || genome.getNocturnal();
	}

	private boolean canWorkUnderground(IBeeModifier beeModifier) {
		return genome.getCaveDwelling() || beeModifier.isSunlightSimulated();
	}

	private boolean canFlyInRain(IBeeModifier beeModifier) {
		return genome.getTolerantFlyer() || beeModifier.isSealed();
	}

	private boolean isSuitableBiome(BiomeGenBase biome) {
		if (biome == null) {
			return false;
		}

		EnumTemperature temperature = EnumTemperature.getFromBiome(biome);
		EnumHumidity humidity = EnumHumidity.getFromValue(biome.rainfall);
		return isSuitableClimate(temperature, humidity);
	}

	private boolean isSuitableClimate(EnumTemperature temperature, EnumHumidity humidity) {
		return AlleleManager.climateHelper.isWithinLimits(temperature, humidity,
				genome.getPrimary().getTemperature(), genome.getToleranceTemp(),
				genome.getPrimary().getHumidity(), genome.getToleranceHumid());
	}

	@Override
	public ArrayList<BiomeGenBase> getSuitableBiomes() {
		ArrayList<BiomeGenBase> suitableBiomes = new ArrayList<>();
		for (BiomeGenBase biome : BiomeGenBase.getBiomeGenArray()) {
			if (isSuitableBiome(biome)) {
				suitableBiomes.add(biome);
			}
		}

		return suitableBiomes;
	}

	@Override
	public void addTooltip(List<String> list) {
		final EnumChatFormatting gray = EnumChatFormatting.DARK_GRAY;
		EnumChatFormatting colour = EnumChatFormatting.GRAY;
		IAllele active, inactive;
		StringBuilder gui;
		String value;
		
		// No info 4 u!
		if (!isAnalyzed) {
			list.add("<" + StringUtil.localize("gui.unknown") + ">");
			return;
		}

		// You analyzed it? Juicy tooltip coming up!
		IAlleleBeeSpecies primary = genome.getPrimary();
		IAlleleBeeSpecies secondary = genome.getSecondary();
		if (!isPureBred(EnumBeeChromosome.SPECIES)) {
			list.add(EnumChatFormatting.BLUE + StringUtil.localize("bees.hybrid").replaceAll("%PRIMARY", primary.getName()).replaceAll("%SECONDARY", secondary.getName()));
		}

		if (generation > 0) {
			EnumRarity rarity;
			if (generation >= 1000) {
				rarity = EnumRarity.epic;
			} else if (generation >= 100) {
				rarity = EnumRarity.rare;
			} else if (generation >= 10) {
				rarity = EnumRarity.uncommon;
			} else {
				rarity = EnumRarity.common;
			}

			String generationString = rarity.rarityColor + StringUtil.localizeAndFormat("gui.beealyzer.generations", generation);
			list.add(generationString);
		}
		
		// lifespan
		active = genome.getActiveAllele(EnumBeeChromosome.LIFESPAN);
		inactive = genome.getInactiveAllele(EnumBeeChromosome.LIFESPAN);
		gui = new StringBuilder();
		gui.append(EnumChatFormatting.DARK_GREEN);
		gui.append(StringUtil.localize("gui.life"));
		gui.append(": ");
		this.Allele(gui, active.getName(), inactive.getName());
		list.add(gui.toString());

		// speed
		active = genome.getActiveAllele(EnumBeeChromosome.SPEED);
		inactive = genome.getInactiveAllele(EnumBeeChromosome.SPEED);
		value = "tooltip.worker." + active.getUnlocalizedName().replaceAll("(.*)\\.", "");
		if (StringUtil.canTranslate(value)) {
			list.add(StringUtil.localize(value));
		} else {
			gui = new StringBuilder();
			gui.append(EnumChatFormatting.DARK_RED);
			gui.append(StringUtil.localize("gui.worker"));
			gui.append(": ");
			this.Allele(gui, active.getName(), inactive.getName());
			list.add(gui.toString());
		}

		// tempTolerance
		active = getGenome().getActiveAllele(EnumBeeChromosome.TEMPERATURE_TOLERANCE);
		inactive = getGenome().getInactiveAllele(EnumBeeChromosome.TEMPERATURE_TOLERANCE);
		gui = new StringBuilder();
		gui.append(EnumChatFormatting.GREEN);
		gui.append("T: ");
		gui.append(AlleleManager.climateHelper.toDisplay(primary.getTemperature()));
		gui.append(" / ");
		this.Allele(gui, active.getName(), inactive.getName());
		list.add(gui.toString());

		// humidTolerance
		active = getGenome().getActiveAllele(EnumBeeChromosome.HUMIDITY_TOLERANCE);
		inactive = getGenome().getInactiveAllele(EnumBeeChromosome.HUMIDITY_TOLERANCE);
		gui = new StringBuilder();
		gui.append(EnumChatFormatting.GREEN);
		gui.append("H: ");
		gui.append(AlleleManager.climateHelper.toDisplay(primary.getHumidity()));
		gui.append(" / ");
		this.Allele(gui, active.getName(), inactive.getName());
		list.add(gui.toString());

		// flowers
		active = genome.getActiveAllele(EnumBeeChromosome.FLOWER_PROVIDER);
		inactive = genome.getInactiveAllele(EnumBeeChromosome.FLOWER_PROVIDER);
		gui = new StringBuilder();
		gui.append(EnumChatFormatting.LIGHT_PURPLE);
		gui.append(StringUtil.localize("gui.flowers.tooltip"));
		gui.append(": ");
		this.Allele(gui, active.getName(), inactive.getName());
		list.add(gui.toString());

		// fertility
		int num1 = ((IAlleleInteger)genome.getActiveAllele(EnumBeeChromosome.FERTILITY)).getValue();
		int num0 = ((IAlleleInteger)genome.getInactiveAllele(EnumBeeChromosome.FERTILITY)).getValue();
		colour = EnumChatFormatting.LIGHT_PURPLE;
		gui = new StringBuilder();
		gui.append(colour);
		gui.append(StringUtil.localize("gui.fertility.tooltip"));
		gui.append(": ");
		if ((num1 ^ num0) > 0) {
			if (num1 > num0) {
				gui.append(gray);
				gui.append(num0);
				gui.append(colour);
				gui.append(" - ");
				gui.append(num1);
			} else {
				gui.append(num1);
				gui.append(" - ");
				gui.append(gray);
				gui.append(num0);
			}
		} else {
			gui.append(num1);
		}
		list.add(gui.toString());

		// -- start -- 
		// diurnal
		AlleleBoolean inactiveBoolean;
		gui = new StringBuilder();
		gui.append(EnumChatFormatting.AQUA);
		gui.append(StringUtil.localize("gui.diurnal"));
		
		// nocturnal
		inactiveBoolean = (AlleleBoolean)genome.getInactiveAllele(EnumBeeChromosome.NOCTURNAL);
		this.Allele(gui, genome.getNocturnal(), inactiveBoolean.getValue(), "gui.nocturnal", EnumChatFormatting.RED);

		// flyer
		inactiveBoolean = (AlleleBoolean)genome.getInactiveAllele(EnumBeeChromosome.TOLERANT_FLYER);
		this.Allele(gui, genome.getTolerantFlyer(), inactiveBoolean.getValue(), "gui.flyer.tooltip", EnumChatFormatting.WHITE);

		// cave
		inactiveBoolean = (AlleleBoolean)genome.getInactiveAllele(EnumBeeChromosome.CAVE_DWELLING);
		this.Allele(gui, genome.getCaveDwelling(), inactiveBoolean.getValue(), "gui.cave", EnumChatFormatting.BLUE);
		
		list.add(gui.toString());
		// -- end --
		
		// area
		colour = EnumChatFormatting.DARK_AQUA;
		active = genome.getActiveAllele(EnumBeeChromosome.TERRITORY);
		inactive = genome.getInactiveAllele(EnumBeeChromosome.TERRITORY);
		gui = new StringBuilder();
		gui.append(colour);
		gui.append(StringUtil.localize("gui.area"));
		gui.append(": ");
		gui.append(active.getName());
		gui.append(' ');
		gui.append(inactive.getName());
		list.add(gui.toString());
		
		// effect
		final String alleleEffect = "forestry.allele.effect.none";
		gui = new StringBuilder();
		active = genome.getActiveAllele(EnumBeeChromosome.EFFECT);
		inactive = genome.getInactiveAllele(EnumBeeChromosome.EFFECT);
		Boolean a = !active.getUnlocalizedName().equals(alleleEffect);
		Boolean b = !inactive.getUnlocalizedName().equals(alleleEffect);
		if (a || b)
		{
			colour = EnumChatFormatting.GOLD;
			gui.append(colour);
			gui.append(StringUtil.localize("gui.effect.tooltip"));
			// gui.append(EnumChatFormatting.WHITE);
			gui.append(": ");
			if (a) {
				gui.append(colour);
				gui.append(active.getName());
				if (b) {
					gui.append(' ');
				}
			}
			if (b) {
				gui.append(gray);
				gui.append(inactive.getName());
			}
			list.add(gui.toString());
		}
	}
	
	private void Allele(StringBuilder value, boolean active, boolean inactive, String guiKey, EnumChatFormatting colour) {
		if (active || inactive)
		{
			value.append(' ');
			value.append(colour);
			value.append(StringUtil.localize(guiKey));
			if (active ^ inactive)
			{
				final EnumChatFormatting gray = EnumChatFormatting.DARK_GRAY;
				value.append('(');
				value.append(active ? colour : gray);
				value.append(active ? '1' : '0');
				value.append(inactive ? colour : gray);
				value.append(inactive ? '1' : '0');
				value.append(colour);
				value.append(')');
			}
		}
	}
	
	private void Allele(StringBuilder value, String active, String inactive) {
		final EnumChatFormatting gray = EnumChatFormatting.DARK_GRAY;
		value.append(active);
		if (!active.equals(inactive)) {
			value.append(' ');
			value.append(gray);
			value.append(inactive);
		}
	}

	@Override
	public void age(World world, float housingLifespanModifier) {
		IBeekeepingMode mode = BeeManager.beeRoot.getBeekeepingMode(world);
		IBeeModifier beeModifier = mode.getBeeModifier();
		float finalModifier = housingLifespanModifier * beeModifier.getLifespanModifier(genome, mate, housingLifespanModifier);

		super.age(world, finalModifier);
	}

	// / PRODUCTION
	@Override
	public ItemStack[] getProduceList() {
		ArrayList<ItemStack> products = new ArrayList<>();

		IAlleleBeeSpecies primary = genome.getPrimary();
		IAlleleBeeSpecies secondary = genome.getSecondary();

		products.addAll(primary.getProductChances().keySet());

		Set<ItemStack> secondaryProducts = secondary.getProductChances().keySet();
		// Remove duplicates
		for (ItemStack second : secondaryProducts) {
			boolean skip = false;

			for (ItemStack compare : products) {
				if (second.isItemEqual(compare)) {
					skip = true;
					break;
				}
			}

			if (!skip) {
				products.add(second);
			}

		}

		return products.toArray(new ItemStack[products.size()]);
	}

	@Override
	public ItemStack[] getSpecialtyList() {
		Set<ItemStack> specialties = genome.getPrimary().getSpecialtyChances().keySet();
		return specialties.toArray(new ItemStack[specialties.size()]);
	}

	@Override
	public ItemStack[] produceStacks(IBeeHousing housing) {
		if (housing == null) {
			Log.warning("Failed to produce in an apiary because the beehousing was null.");
			return null;
		}
		IBeekeepingMode mode = BeeManager.beeRoot.getBeekeepingMode(housing.getWorld());
		if (mode == null) {
			Log.warning("Failed to produce in an apiary because the beekeeping mode was null.");
			return null;
		}

		List<ItemStack> products = new ArrayList<>();

		IAlleleBeeSpecies primary = genome.getPrimary();
		IAlleleBeeSpecies secondary = genome.getSecondary();

		// All work and no play makes queen a dull girl.
		if (mode.isOverworked(this, housing)) {
			setIsNatural(false);
		}

		IBeeModifier beeHousingModifier = BeeManager.beeRoot.createBeeHousingModifier(housing);
		IBeeModifier beeModeModifier = mode.getBeeModifier();

		// Bee genetic speed * beehousing * beekeeping mode
		float speed = genome.getSpeed() * beeHousingModifier.getProductionModifier(genome, 1f) * beeModeModifier.getProductionModifier(genome, 1f);

		// / Primary Products
		for (Map.Entry<ItemStack, Float> entry : primary.getProductChances().entrySet()) {
			if (housing.getWorld().rand.nextFloat() < entry.getValue() * speed) {
				products.add(entry.getKey().copy());
			}
		}
		// / Secondary Products
		for (Map.Entry<ItemStack, Float> entry : secondary.getProductChances().entrySet()) {
			if (housing.getWorld().rand.nextFloat() < Math.round(entry.getValue() / 2) * speed) {
				products.add(entry.getKey().copy());
			}
		}

		// / Specialty products
		if (primary.isJubilant(genome, housing) && secondary.isJubilant(genome, housing)) {
			for (Map.Entry<ItemStack, Float> entry : primary.getSpecialtyChances().entrySet()) {
				if (housing.getWorld().rand.nextFloat() < entry.getValue() * speed) {
					products.add(entry.getKey().copy());
				}
			}
		}

		ItemStack[] productsArray = products.toArray(new ItemStack[products.size()]);
		ChunkCoordinates housingCoordinates = housing.getCoordinates();
		return genome.getFlowerProvider().affectProducts(housing.getWorld(), this, housingCoordinates.posX, housingCoordinates.posY, housingCoordinates.posZ, productsArray);
	}

	/* REPRODUCTION */
	@Override
	public IBee spawnPrincess(IBeeHousing housing) {

		// We need a mated queen to produce offspring.
		if (mate == null) {
			return null;
		}

		// Fatigued queens do not produce princesses.
		if (BeeManager.beeRoot.getBeekeepingMode(housing.getWorld()).isFatigued(this, housing)) {
			return null;
		}

		return createOffspring(housing, getGeneration() + 1);
	}

	@Override
	public IBee[] spawnDrones(IBeeHousing housing) {

		World world = housing.getWorld();

		// We need a mated queen to produce offspring.
		if (mate == null) {
			return null;
		}

		List<IBee> bees = new ArrayList<>();

		ChunkCoordinates housingCoordinates = housing.getCoordinates();
		int toCreate = BeeManager.beeRoot.getBeekeepingMode(world).getFinalFertility(this, world, housingCoordinates.posX, housingCoordinates.posY, housingCoordinates.posZ);

		if (toCreate <= 0) {
			toCreate = 1;
		}

		for (int i = 0; i < toCreate; i++) {
			IBee offspring = createOffspring(housing, 0);
			if (offspring != null) {
				offspring.setIsNatural(true);
				bees.add(offspring);
			}
		}

		if (bees.size() > 0) {
			return bees.toArray(new IBee[bees.size()]);
		} else {
			return null;
		}
	}

	private IBee createOffspring(IBeeHousing housing, int generation) {

		World world = housing.getWorld();

		IChromosome[] chromosomes = new IChromosome[genome.getChromosomes().length];
		IChromosome[] parent1 = genome.getChromosomes();
		IChromosome[] parent2 = mate.getChromosomes();

		// Check for mutation. Replace one of the parents with the mutation
		// template if mutation occured.
		IChromosome[] mutated1 = mutateSpecies(housing, genome, mate);
		if (mutated1 != null) {
			parent1 = mutated1;
		}
		IChromosome[] mutated2 = mutateSpecies(housing, mate, genome);
		if (mutated2 != null) {
			parent2 = mutated2;
		}

		for (int i = 0; i < parent1.length; i++) {
			if (parent1[i] != null && parent2[i] != null) {
				chromosomes[i] = Chromosome.inheritChromosome(world.rand, parent1[i], parent2[i]);
			}
		}

		IBeekeepingMode mode = BeeManager.beeRoot.getBeekeepingMode(world);
		return new Bee(new BeeGenome(chromosomes), mode.isNaturalOffspring(this), generation);
	}

	private static IChromosome[] mutateSpecies(IBeeHousing housing, IBeeGenome genomeOne, IBeeGenome genomeTwo) {

		World world = housing.getWorld();

		IChromosome[] parent1 = genomeOne.getChromosomes();
		IChromosome[] parent2 = genomeTwo.getChromosomes();

		IBeeGenome genome0;
		IBeeGenome genome1;

		IAlleleBeeSpecies allele0;
		IAlleleBeeSpecies allele1;

		if (world.rand.nextBoolean()) {
			allele0 = (IAlleleBeeSpecies) parent1[EnumBeeChromosome.SPECIES.ordinal()].getPrimaryAllele();
			allele1 = (IAlleleBeeSpecies) parent2[EnumBeeChromosome.SPECIES.ordinal()].getSecondaryAllele();

			genome0 = genomeOne;
			genome1 = genomeTwo;
		} else {
			allele0 = (IAlleleBeeSpecies) parent2[EnumBeeChromosome.SPECIES.ordinal()].getPrimaryAllele();
			allele1 = (IAlleleBeeSpecies) parent1[EnumBeeChromosome.SPECIES.ordinal()].getSecondaryAllele();

			genome0 = genomeTwo;
			genome1 = genomeOne;
		}

		GameProfile playerProfile = housing.getOwner();
		IApiaristTracker breedingTracker = BeeManager.beeRoot.getBreedingTracker(world, playerProfile);

		List<IMutation> combinations = BeeManager.beeRoot.getCombinations(allele0, allele1, true);
		for (IMutation mutation : combinations) {
			IBeeMutation beeMutation = (IBeeMutation) mutation;

			float chance = beeMutation.getChance(housing, allele0, allele1, genome0, genome1);
			if (chance <= 0) {
				continue;
			}

			// boost chance for researched mutations
			if (breedingTracker.isResearched(beeMutation)) {
				float mutationBoost = chance * (Config.researchMutationBoostMultiplier - 1.0f);
				mutationBoost = Math.min(Config.maxResearchMutationBoostPercent, mutationBoost);
				chance += mutationBoost;
			}

			if (chance > world.rand.nextFloat() * 100) {
				breedingTracker.registerMutation(mutation);
				return BeeManager.beeRoot.templateAsChromosomes(mutation.getTemplate());
			}
		}

		return null;
	}

	/* FLOWERS */
	@Override
	public IIndividual retrievePollen(IBeeHousing housing) {

		IBeeModifier beeModifier = BeeManager.beeRoot.createBeeHousingModifier(housing);

		int chance = Math.round(genome.getFlowering() * beeModifier.getFloweringModifier(getGenome(), 1f));

		World world = housing.getWorld();
		Random random = world.rand;

		// Correct speed
		if (random.nextInt(100) >= chance) {
			return null;
		}

		Vect area = getArea(genome, beeModifier);
		Vect offset = new Vect(-area.x / 2, -area.y / 4, -area.z / 2);
		Vect housingPos = new Vect(housing.getCoordinates());

		IIndividual pollen = null;

		for (int i = 0; i < 20; i++) {
			Vect randomPos = Vect.getRandomPositionInArea(random, area);
			Vect blockPos = Vect.add(housingPos, randomPos, offset);
			TileEntity tile = world.getTileEntity(blockPos.x, blockPos.y, blockPos.z);

			if (tile instanceof IPollinatable) {
				IPollinatable pitcher = (IPollinatable) tile;
				if (genome.getFlowerProvider().isAcceptedPollinatable(world, pitcher)) {
					pollen = pitcher.getPollen();
				}
			} else {
				pollen = GeneticsUtil.getErsatzPollen(world, blockPos.x, blockPos.y, blockPos.z);
			}

			if (pollen != null) {
				return pollen;
			}
		}

		return null;
	}

	@Override
	public boolean pollinateRandom(IBeeHousing housing, IIndividual pollen) {

		IBeeModifier beeModifier = BeeManager.beeRoot.createBeeHousingModifier(housing);

		int chance = (int) (genome.getFlowering() * beeModifier.getFloweringModifier(getGenome(), 1f));

		World world = housing.getWorld();
		Random random = world.rand;

		// Correct speed
		if (random.nextInt(100) >= chance) {
			return false;
		}

		Vect area = getArea(genome, beeModifier);
		Vect offset = new Vect(-area.x / 2, -area.y / 4, -area.z / 2);
		Vect housingPos = new Vect(housing.getCoordinates());

		for (int i = 0; i < 30; i++) {

			Vect randomPos = Vect.getRandomPositionInArea(random, area);
			Vect posBlock = Vect.add(housingPos, randomPos, offset);

			ICheckPollinatable checkPollinatable = GeneticsUtil.getCheckPollinatable(world, posBlock.x, posBlock.y, posBlock.z);
			if (checkPollinatable == null) {
				continue;
			}

			if (!genome.getFlowerProvider().isAcceptedPollinatable(world, new FakePollinatable(checkPollinatable))) {
				continue;
			}
			if (!checkPollinatable.canMateWith(pollen)) {
				continue;
			}

			IPollinatable realPollinatable = GeneticsUtil.getOrCreatePollinatable(housing.getOwner(), world, posBlock.x, posBlock.y, posBlock.z);

			if (realPollinatable != null) {
				realPollinatable.mateWith(pollen);
				return true;
			}
		}

		return false;
	}

	@Override
	public void plantFlowerRandom(IBeeHousing housing) {

		IBeeModifier beeModifier = BeeManager.beeRoot.createBeeHousingModifier(housing);

		int chance = Math.round(genome.getFlowering() * beeModifier.getFloweringModifier(getGenome(), 1f));

		World world = housing.getWorld();
		Random random = world.rand;

		// Correct speed
		if (random.nextInt(100) >= chance) {
			return;
		}

		// Gather required info
		IFlowerProvider provider = genome.getFlowerProvider();
		Vect area = getArea(genome, beeModifier);
		Vect offset = new Vect(-area.x / 2, -area.y / 4, -area.z / 2);
		Vect housingPos = new Vect(housing.getCoordinates());

		for (int i = 0; i < 10; i++) {
			Vect randomPos = Vect.getRandomPositionInArea(random, area);
			Vect posBlock = Vect.add(housingPos, randomPos, offset);

			if (FlowerManager.flowerRegistry.growFlower(provider.getFlowerType(), world, this, posBlock.x, posBlock.y, posBlock.z)) {
				break;
			}
		}
	}

	private static Vect getArea(IBeeGenome genome, IBeeModifier beeModifier) {
		int[] genomeTerritory = genome.getTerritory();
		float housingModifier = beeModifier.getTerritoryModifier(genome, 1f);
		return new Vect(genomeTerritory).multiply(housingModifier * 3.0f);
	}
}
