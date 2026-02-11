package cz.preclikos.tvhstream.di

import coil3.ImageLoader
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.player.PlayerSession
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.services.StatusService
import cz.preclikos.tvhstream.services.StatusServiceImpl
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.stores.ChannelSelectionStore
import cz.preclikos.tvhstream.ui.components.buildImageLoader
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single<CoroutineDispatcher>(qualifier = named("io")) { Dispatchers.IO }

    single { HtspService(ioDispatcher = get(named("io"))) }
    single {
        TvhRepository(
            htsp = get(), ioDispatcher = get(named("io")),
            statusService = get()
        )
    }

    single<StatusService> { StatusServiceImpl() }

    single { SettingsStore(context = get()) }
    single { SecurePasswordStore(context = get()) }

    single { ChannelSelectionStore() }

    single { PlayerSession(get()) }

    single<ImageLoader> {
        buildImageLoader(
            context = androidContext(),
            htsp = get<HtspService>()
        )
    }

    viewModel {
        AppConnectionViewModel(
            htsp = get(),
            repo = get(),
            settings = get(),
            passwords = get(),
            statusService = get()
        )
    }
    viewModel { VideoPlayerViewModel(playerSession = get(), repo = get(), htspService = get()) }
    viewModel { ChannelsViewModel(repo = get()) }
}
